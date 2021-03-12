package polynote.server

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import fs2.concurrent.Topic
import org.scalacheck.Gen
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FreeSpec, Matchers}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import polynote.kernel.Kernel.Factory
import polynote.kernel.environment.{Config, PublishMessage, PublishResult}
import polynote.kernel.interpreter.Interpreter
import polynote.kernel.remote.SocketTransport.DeploySubprocess
import polynote.kernel.remote.{RemoteKernel, SocketTransport, SocketTransportServer}
import polynote.kernel.remote.SocketTransport.DeploySubprocess.DeployJava
import polynote.kernel.util.Publish
import polynote.kernel.{BaseEnv, CellEnv, GlobalEnv, Kernel, KernelBusyState, KernelError, KernelInfo, KernelStatusUpdate, LocalKernel, LocalKernelFactory, Output, StreamThrowableOps}
import polynote.messages.{CellID, ContentEdit, ContentEdits, Delete, Insert, Message, Notebook, NotebookCell, NotebookUpdate, ShortList, UpdateCell}
import polynote.server.auth.UserIdentity
import polynote.testing.ExtConfiguredZIOSpec
import polynote.testing.kernel.MockNotebookRef
import polynote.util.VersionBuffer
import zio.clock.Clock
import zio.duration.Duration
import zio.stream.{Take, ZStream}
import zio.{Chunk, Fiber, Has, Promise, Queue, RIO, Ref, Semaphore, Tag, Task, UIO, ULayer, URIO, ZIO, ZLayer}
import zio.interop.catz.taskConcurrentInstance
import zio.random, random.Random

import scala.collection.mutable.ListBuffer

class KernelPublisherIntegrationTest extends FreeSpec with Matchers with ExtConfiguredZIOSpec[Interpreter.Factories] with MockFactory with ScalaCheckDrivenPropertyChecks {
  val tagged: Tag[Interpreter.Factories] = implicitly

  override lazy val configuredEnvLayer: ZLayer[zio.ZEnv with Config, Nothing, Interpreter.Factories] = ZLayer.succeed(Map.empty)

  private def mkStubKernel = {
    val stubKernel = stub[Kernel]
    stubKernel.shutdown _ when () returns ZIO.unit
    stubKernel.awaitClosed _ when () returns ZIO.unit
    stubKernel.init _ when () returns ZIO.unit
    stubKernel.info _ when () returns ZIO.succeed(KernelInfo())
    stubKernel
  }

  private val bq = mock[Topic[Task, Option[Message]]]

  "KernelPublisher" - {

    "collapses carriage returns in saved notebook" in {
      val kernel          = mkStubKernel
      val kernelFactory   = Kernel.Factory.const(kernel)
      kernel.queueCell _ when (CellID(0)) returns ZIO.environment[CellEnv].map {
        env =>
          ZIO.foreach_(0 until 100) {
            i => PublishResult(Output("text/plain; rel=stdout", s"$i\r"))
          }.flatMap {
            _ => PublishResult(Output("text/plain; rel=stdout", "end\n"))
          }.provideLayer(ZLayer.succeedMany(env))
      }
      val notebook        = Notebook("/i/am/fake.ipynb", ShortList(List(NotebookCell(CellID(0), "scala", ""))), None)
      val ref             = MockNotebookRef(notebook).runIO()
      val kernelPublisher = KernelPublisher(ref, bq).runWith(kernelFactory)
      kernelPublisher.queueCell(CellID(0)).flatten.runWith(kernelFactory)
      kernelPublisher.latestVersion.runIO()._2.cells.head.results should contain theSameElementsAs Seq(
        Output("text/plain; rel=stdout", "end\n")
      )
    }

    "gracefully handles death of kernel" in {
      val deploy          = new DeploySubprocess(new DeployJava[LocalKernelFactory])
      val transport       = new SocketTransport(deploy)
      val notebook        = Notebook("/i/am/fake.ipynb", ShortList(Nil), None)
      val ref             = MockNotebookRef(notebook).runIO()
      val kernelFactory   = RemoteKernel.factory(transport)
      val kernelPublisher = KernelPublisher(ref, bq).runWith(kernelFactory)
      val kernel          = kernelPublisher.kernel.runWith(kernelFactory).asInstanceOf[RemoteKernel[InetSocketAddress]]
      val process         = kernel.transport.asInstanceOf[SocketTransportServer].process

      val collectStatus = kernelPublisher.status.subscribe(5).interruptWhen(kernelPublisher.closed.await.either).compile.toList.forkDaemon.runIO()

      process.kill().runIO()
      assert(process.awaitExit(1, TimeUnit.SECONDS).runIO().nonEmpty)

      val kernel2 = kernelPublisher.kernel
        .repeatUntil(_ ne kernel)
        .timeout(Duration(20, TimeUnit.SECONDS))
        .someOrFail(new Exception("Kernel should have changed; didn't change after 5 seconds"))
        .runWith(kernelFactory)

      assert(!(kernel2 eq kernel), "Kernel should have changed")
      kernelPublisher.close().runIO()
      val statusUpdates = collectStatus.join.runIO()

      // should have gotten a notification that the kernel became dead
      statusUpdates should contain (KernelBusyState(busy = false, alive = false))

      // should have gotten some kernel error
      statusUpdates.collect {
        case KernelError(err) => err
      }.size shouldEqual 1

    }

    "gracefully handles startup failure of kernel" in {
      val stubKernel = mkStubKernel

      case class FailedToStart() extends Exception("The kernel fails to start. What do you do?")

      val failingKernelFactory: Factory.Service = new Factory.Service {
        private var attempted = 0
        override def apply(): RIO[BaseEnv with GlobalEnv with CellEnv, Kernel] =
          ZIO(attempted).bracket(n => ZIO.effectTotal(attempted = n + 1)) {
            case 0 => ZIO.fail(FailedToStart())
            case n => ZIO.succeed(stubKernel)
          }
      }

      val notebook        = Notebook("/i/am/fake.ipynb", ShortList(Nil), None)
      val ref             = MockNotebookRef(notebook).runIO()
      val kernelPublisher = KernelPublisher(ref, bq).runWith(failingKernelFactory)
      val stopStatus = Promise.make[Throwable, Unit].runIO()
      val collectStatus = kernelPublisher.status.subscribe(5).interruptWhen(stopStatus.await.either).compile.toList.forkDaemon.runIO()

      a [FailedToStart] should be thrownBy {
        kernelPublisher.kernel.runWith(failingKernelFactory)
      }

      val kernel2 = kernelPublisher.kernel.runWith(failingKernelFactory)
      assert(kernel2 eq stubKernel)
      kernelPublisher.close().runIO()
      stopStatus.succeed(()).runIO()
      val statusUpdates = collectStatus.join.runIO()

      // should have gotten the original startup error in status updates
      statusUpdates should contain (KernelError(FailedToStart()))

    }

    "handles concurrent edits" in {
      // start up a KernelPublisher and two subscribers. Then, simulate keyboard-mashing from each subscriber
      // and track their local state using the same logic as the front-end. At the end, the server and both
      // clients should have the same state.

      val kernel          = mkStubKernel
      val kernelFactory   = Kernel.Factory.const(kernel)
      val cell = NotebookCell(
        CellID(0), "scala",
        """text in cell""".stripMargin
      )
      val notebook        = Notebook("/i/am/fake.ipynb", ShortList(List(cell)), None)
      val ref             = MockNotebookRef(notebook).runIO()
      val kernelPublisher = KernelPublisher(ref, bq).runWith(kernelFactory)

      val client1 = new Client(Ref.make(notebook).runIO(), kernelPublisher, kernelFactory)
      val client2 = new Client(Ref.make(notebook).runIO(), kernelPublisher, kernelFactory)
      val startTime = zio.clock.currentTime(TimeUnit.MILLISECONDS).runIO()
      // listen to the canonical updates from the publisher
      val serverEdits = new ListBuffer[(Int, NotebookUpdate, Long)]
      val stopListening = Promise.make[Throwable, Unit].runIO()
      val listener = kernelPublisher.broadcastUpdates.subscribe(128).unNone.terminateWhen(stopListening)(taskConcurrentInstance[Any]).evalMap {
        edit => zio.clock.currentTime(TimeUnit.MILLISECONDS).flatMap {
          time => ZIO.effectTotal(serverEdits += ((edit._1, edit._2, time)))
        }
      }.compile.drain.forkDaemon.runIO()

      val result = for {
        receive1 <- client1.receive.fork
        receive2 <- client2.receive.fork
        send1    <- client1.send.fork
        send2    <- client2.send.fork
        _    <- ZIO.sleep(Duration.fromMillis(1000))
        _    <- send1.interrupt
        _    <- send2.interrupt
        _    <- client1.inbound.offer(Take.end)
        _    <- client2.inbound.offer(Take.end)
        _    <- receive1.join
        _    <- receive2.join
        res1 <- client1.results
        res2 <- client2.results
        serv <- kernelPublisher.versionedNotebook.get.map(_.cells.head.content.toString)
        _    <- stopListening.succeed(())
        _    <- listener.join
      } yield (res1, res2, serv)


      val ((res1, edits1), (res2, edits2), serv) = (random.setSeed(0L) *> result).runIO()

      val expectedServerContent = serverEdits.map(_._2).foldLeft(notebook)((notebook, update) => update.applyTo(notebook)).cells.head.content.toString
      serv shouldEqual expectedServerContent
      res1 shouldEqual serv
      res2 shouldEqual serv
    }

  }

  val genLetter = Gen.alphaNumChar.map {
    char => (currentOffset: Int) => (currentOffset + 1, Insert(currentOffset, char.toString))
  }

  val genKeystroke: Gen[Int => (Int, ContentEdit)] = Gen.frequency(
    (8, genLetter),
    (2, Gen.const(currentOffset => (currentOffset - 1, Delete(currentOffset - 1, 1))))
  )

  val genLatency = Gen.infiniteStream(Gen.choose(40, 220))

  private def latency[A]: ZStream[Clock with Random, Nothing, Take[Nothing, A]] = ZStream.repeatEffect[Clock with Random, Nothing, Take[Nothing, A]](
    random.nextLongBetween(180, 220).map(Duration.fromMillis).flatMap(dur => ZIO.sleep(dur)).as(Take.chunk(Chunk.empty))
  )

  private def withLatency[E, A](stream: ZStream[Any, E, Take[Nothing, A]]): ZStream[zio.random.Random with Clock, E, Take[Nothing, A]] =
    ZStream.mergeAllUnbounded()(latency[A], stream)

  class Client(notebook: Ref[Notebook], publisher: KernelPublisher, kernelFactory: Kernel.Factory.Service, latency: Duration = Duration.fromMillis(50L)) {
    val inbound: Queue[Take[Nothing, Message]] = Queue.unbounded[Take[Nothing, Message]].runIO()
    private val outbound = Queue.unbounded[Take[Nothing, NotebookUpdate]].runIO()
    private val versionBuffer: VersionBuffer[NotebookUpdate] = new VersionBuffer[NotebookUpdate]()
    private val localVersionRef = Ref.make(0).runIO()
    private val globalVersionRef = Ref.make(0).runIO()
    private val currentOffset = random.nextIntBetween(0, notebook.get.runIO().cells.head.content.size).flatMap(Ref.make).runIO()

    // a semaphore to simulate JavaScript single-threadedness
    private val js = Semaphore.make(1L).runIO()

    // a semaphore for outbound network messages
    private val outboundSem = Semaphore.make(1L).runIO()

    private val publishEnv: ULayer[PublishMessage] = ZLayer.succeed(inbound)
    private val identityEnv: ULayer[UserIdentity] = ZLayer.succeed(None)
    val env: ZLayer[Any, Nothing, Kernel.Factory with PublishMessage with UserIdentity] = ZLayer.succeed(kernelFactory) ++ publishEnv ++ identityEnv
    private val subscriber = publisher.subscribe().runWithLayer(env)

    // send all the requests in the queue to the subscriber (server), with specified latency
    private val outboundSender = withLatency(ZStream.fromQueueWithShutdown(outbound))
      .flattenTake
      .foreach(subscriber.update)
      .ensuring(outbound.shutdown).forkDaemon.runIO()

    private val edits = new ListBuffer[ContentEdit]
    private val allUpdates = new ListBuffer[ContentEdits]
    val globalVersions = new ListBuffer[(Int, String)]

    def content: String = notebook.get.runIO().cells.head.content.toString

    def runUntil(promise: Promise[Nothing, Unit]) = for {
      receiving <- receive.fork
      _          = edits.clear()
      sending   <- send.fork
      _         <- promise.await
      _         <- sending.interrupt
      _         <- outbound.offer(Take.end)
      _         <- outboundSender.join
      _         <- inbound.offer(Take.end)
      _         <- receiving.join
      notebook  <- notebook.get
    } yield (notebook.cells.head.content.toString, edits.toList)

    def results: UIO[(String, List[ContentEdits])] = notebook.get.map(_.cells.head.content.toString -> allUpdates.toList)

    def receive: URIO[Random with Clock, Unit] = {
      // receive messages (with simulated latency) from the server and try to process them in the same fashion as the client
      withLatency(ZStream.fromQueue(inbound)).flattenTake.mapM {
        case update: NotebookUpdate =>
          js.withPermit {
            //ZIO.effectTotal(println(s"""> ${subscriber.id} Received $update""")) *>
            notebook.get.flatMap { prev =>
              localVersionRef.get.flatMap {
                localVersion =>
                  val rebased = if (update.localVersion < localVersion) {
                    val edits = update.asInstanceOf[UpdateCell].edits
                    //println(s"""> ${subscriber.id} rebasing ${edits} from L${update.localVersion} to L$localVersion on "${prev.cells.head.content}"""")
                    val rebaseOnto = versionBuffer.getRange(update.localVersion, localVersion) //.dropWhile(_.localVersion == localVersion)
                    rebaseOnto.foldLeft(update) {
                      case (accum, next@UpdateCell(_, _, _, edits, _)) =>
                        val rebased = accum.rebase(next)
                        val rebasedEdits = rebased.asInstanceOf[UpdateCell].edits
                        //println(s"> ${subscriber.id} | $next => $rebasedEdits")
                        rebased
                    }
                  } else update
                  globalVersionRef.set(rebased.globalVersion) *>
                    notebook.updateAndGet(nb => rebased.applyTo(nb)).tap {
                      nb => ZIO.effectTotal {
                        //println(s"""> ${subscriber.id} "${nb.cells.head.content.toString}" (at ${rebased.globalVersion}, $localVersion)""")
                        globalVersions += rebased.globalVersion -> nb.cells.head.content.toString
                      }
                    } <* currentOffset.update {
                      currentOffset =>
                        // update my imaginary monaco's position based on received edits and store the received updates
                        rebased match {
                          case UpdateCell(_, _, 0, ce@ContentEdits(edits), _) =>
                            allUpdates += ce
                            edits.foldLeft(currentOffset) {
                              case (currentOffset, Insert(pos, content)) if pos <= currentOffset => currentOffset + content.length
                              case (currentOffset, Delete(pos, length)) if pos <= currentOffset => currentOffset - length
                              case (currentOffset, _) => currentOffset
                            }
                          case _ => currentOffset
                        }
                    }
              }
            }.unit
          }

        case _ => ZIO.unit
      }.runDrain
    }

    def send: URIO[Clock with zio.random.Random, Nothing] = {
      // simulate a keypress
      val typeChar = zio.random.nextPrintableChar.flatMap {
        char =>
          currentOffset.getAndUpdate(_ + 1).map {
            currentOffset => Insert(currentOffset, char.toString)
          }
      }

      val pressBackspace = currentOffset.updateAndGet(_ - 1).map {
        currentOffset => Delete(currentOffset, 1)
      }

      val keypress = random.nextDouble.flatMap {
        case d if d < 0.2 => pressBackspace
        case d            => typeChar
      }

      val sendKeypress = js.withPermit {
        for {
          localVersion  <- localVersionRef.get
          globalVersion <- globalVersionRef.get
          edit          <- keypress
          _              = edits += edit
          ce             = ContentEdits(edit)
          _              = allUpdates += ce
          update         = UpdateCell(globalVersion, localVersion, CellID(0), ce, None)
          _              = versionBuffer.add(localVersion, update)
          _             <- localVersionRef.update(_ + 1)
          prevContent   <- notebook.get.map(_.cells.head.content.toString)
          nb            <- notebook.updateAndGet(update.applyTo)
          content        = nb.cells.head.content.toString
          //_              = println(s"""< ${subscriber.id} $edit "$prevContent" -> "$content"""")
        } yield update
      }.uninterruptible

      // mash keyboard - type roughly 25 characters a second (what I measured when keyboard mashing)
      val mash1 = zio.random.nextLongBetween(30, 40).map(Duration.fromMillis).flatMap(dur => ZIO.sleep(dur)) *>
        (sendKeypress.map(Take.single) >>= outbound.offer)
      mash1.forever
    }
  }

}
