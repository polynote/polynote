package polynote.server

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import fs2.concurrent.Topic
import org.scalacheck.{Gen, Arbitrary, Shrink}
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

import KernelPublisherIntegrationTest._

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

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSize = 0, sizeRange = 5, minSuccessful = 50)

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

      val collectStatus = kernelPublisher.status.subscribe(5).terminateAfterEquals(KernelBusyState(false, false)).compile.toList.forkDaemon.runIO()

      process.kill().runIO()
      assert(process.awaitExit(1, TimeUnit.SECONDS).runIO().nonEmpty)

      val kernel2 = kernelPublisher.kernel
        .repeatUntil(_ ne kernel)
        .timeout(Duration(20, TimeUnit.SECONDS))
        .someOrFail(new Exception("Kernel should have changed; didn't change after 5 seconds"))
        .runWith(kernelFactory)

      assert(!(kernel2 eq kernel), "Kernel should have changed")
      kernelPublisher.close().runIO()
      val statusUpdates = collectStatus.join
        .timeout(Duration(5, TimeUnit.SECONDS))
        .someOrFail(new Exception("Timed out waiting for kernel busy state"))
        .runIO()

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

    "handles concurrent edits" in forAll("Client 1 keystrokes", "Client 2 keystrokes") {
      (client1Init: Init, client2Init: Init) =>
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
        val serverLog = new ListBuffer[(Long, String)]
        val kernelPublisher = KernelPublisher(ref, bq, serverLog).runWith(kernelFactory)

        val client1 = new Client(Ref.make(notebook).runIO(), kernelPublisher, kernelFactory, client1Init)
        val client2 = new Client(Ref.make(notebook).runIO(), kernelPublisher, kernelFactory, client2Init)
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
          _    <- send1.join
          _    <- send2.join
          _    <- (ZIO.sleep(Duration.fromMillis(100)) *> kernelPublisher.updateQueue.size).repeatUntil(_ <= 0)
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


        try {
          val ((res1, edits1), (res2, edits2), serv) = (random.setSeed(0L) *> result).runIO()

          if (res1 != serv || res2 != serv) {
            println("===SHRINK===")
            (client1.log ++ client2.log ++ serverLog).sortBy(_._1).foreach {
              case (t, log) => println(log)
            }
          }
          res1 shouldEqual serv
          res2 shouldEqual serv
        } finally {
          //kernelPublisher.close().runIO()
        }
    }

  }

  class Client(
    notebook: Ref[Notebook],
    publisher: KernelPublisher,
    kernelFactory: Kernel.Factory.Service,
    init: Init
  ) {
    val inbound: Queue[Take[Nothing, Message]] = Queue.unbounded[Take[Nothing, Message]].runIO()
    private val versionBuffer = new SubscriberUpdateBuffer()
    private val localVersionRef = Ref.make(0).runIO()
    private val globalVersionRef = Ref.make(0).runIO()
    private val currentOffset = Ref.make(init.initialOffset).runIO()
    private val Init(_, keypresses, inboundLatencies, outboundLatencies) = init

    // a semaphore to simulate JavaScript single-threadedness
    private val js = Semaphore.make(1L).runIO()

    private val publishEnv: ULayer[PublishMessage] = ZLayer.succeed(inbound)
    private val identityEnv: ULayer[UserIdentity] = ZLayer.succeed(None)
    val env: ZLayer[Any, Nothing, Kernel.Factory with PublishMessage with UserIdentity] = ZLayer.succeed(kernelFactory) ++ publishEnv ++ identityEnv
    val subscriber = publisher.subscribe().runWithLayer(env)

    private val edits = new ListBuffer[ContentEdit]
    private val allUpdates = new ListBuffer[ContentEdits]
    val log = new ListBuffer[(Long, String)]
    val globalVersions = new ListBuffer[(Int, String)]

    def content: String = notebook.get.runIO().cells.head.content.toString

    def results: UIO[(String, List[ContentEdits])] = notebook.get.map(_.cells.head.content.toString -> allUpdates.toList)

    def receive: URIO[Random with Clock, Unit] = {
      // receive messages (with simulated latency) from the server and try to process them in the same fashion as the client
      val latencies = inboundLatencies.iterator
      ZStream.fromQueue(inbound).flattenTake.mapM {
        case update: NotebookUpdate =>
          var logStr = new StringBuilder
          ZIO.sleep(Duration.fromMillis(latencies.next())) *> js.withPermit {
            ZIO.effectTotal(logStr ++= s"""> ${subscriber.id} $update\n""") *>
            notebook.get.flatMap { prev =>
              localVersionRef.get.flatMap {
                localVersion =>
                  val currentTime = System.currentTimeMillis()

                  val rebased = if (update.localVersion < localVersion) {
                    logStr ++= s"""  rebasing $update from L${update.localVersion} to L$localVersion on "${prev.cells.head.content}"\n"""

                    versionBuffer.rebaseThrough(update.withVersions(update.localVersion, update.localVersion), 1, localVersion, Some(logStr), reverse = true)
//                    val rebaseOnto = versionBuffer.getRange(update.localVersion + 1, localVersion)
//                    rebaseOnto.foldLeft(update) {
//                      case (accum, (_, next@UpdateCell(_, _, _, edits, _))) =>
//                        val rebased = accum.rebase(next)
//                        val rebasedEdits = rebased.asInstanceOf[UpdateCell].edits
//                        logStr ++= s"  ${next} => ${rebased}\n"
//                        rebased
//                    }
                    //update.rebaseAll(rebaseOnto, Some(logStr))._1
                  } else {
                    update
                  }
                  globalVersionRef.set(rebased.globalVersion) *>
                    notebook.updateAndGet(nb => rebased.applyTo(nb)).tap {
                      nb => ZIO.effectTotal {
                        logStr ++= s"""  -> "${nb.cells.head.content.toString}" (at ${rebased.globalVersion}, $localVersion)\n"""
                        globalVersions += rebased.globalVersion -> nb.cells.head.content.toString
                        log += ((currentTime, logStr.result()))
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

    def send: URIO[Clock with zio.random.Random, Unit] = {
      if (keypresses.isEmpty)
        return ZIO.unit

      val latencies = outboundLatencies.iterator
      val ops       = keypresses.iterator

      val doBackspace = currentOffset.get.flatMap {
        case i if i <= 0 => ZIO.fail(())
        case i           => currentOffset.updateAndGet(_ - 1).map(offs => Delete(offs, 1))
      }

      val keypress  = ZIO.effectTotal(ops.next()).flatMap {
        case (delay, op)     => ZIO.sleep(Duration.fromMillis(delay)).as(op)
      }.flatMap {
        case Backspace       => doBackspace
        case Keystroke(char) => currentOffset.getAndUpdate(_ + 1).map(i => Insert(i, char.toString))
      }

      val sendKeypress = js.withPermit {
        for {
          prevContent   <- notebook.get.map(_.cells.head.content.toString)
          localVersion  <- localVersionRef.updateAndGet(_ + 1)
          globalVersion <- globalVersionRef.get
          edit          <- keypress
          _              = edits += edit
          ce             = ContentEdits(edit)
          _              = allUpdates += ce
          update         = UpdateCell(globalVersion, localVersion, CellID(0), ce, None)
          nb            <- notebook.updateAndGet(update.applyTo)
          _              = versionBuffer.add(localVersion, (0, update))
          content        = nb.cells.head.content.toString
          str = s"""< ${subscriber.id} $edit "$prevContent" -> "$content""""
          _              = log += ((System.currentTimeMillis(), str))
          //_ = println(str)
        } yield update
      }.uninterruptible

      val sendKeypresses = sendKeypress.flatMap {
        update => ZIO.sleep(Duration.fromMillis(latencies.next())) *> subscriber.update(update).orDie
      }.ignore.repeatWhile(_ => ops.hasNext).uninterruptible

      val outbound: Queue[Take[Nothing, NotebookUpdate]] = Queue.unbounded[Take[Nothing, NotebookUpdate]].runIO()

      // send all the requests in the queue to the subscriber (server), with specified latency
      val outboundSender = ZStream.fromQueueWithShutdown(outbound)
        .flattenTake
        .mapM(msg => ZIO.sleep(Duration.fromMillis(latencies.next())).as(msg))
        .foreach(subscriber.update)
        .ensuring(outbound.shutdown)

      for {
        sending <- outboundSender.fork
        keys    <- sendKeypresses
        _       <- outbound.offer(Take.end)
        _       <- sending.join.orDie
      } yield ()

    }
  }

}

object KernelPublisherIntegrationTest {


  sealed trait Op
  final case object Backspace extends Op
  final case class Keystroke(char: Char) extends Op

  case class Init(
    initialOffset: Int,
    ops: List[(Long, Op)],
    inboundLatencies: Stream[Long],
    outboundLatencies: Stream[Long]
  )

  val genKeypress: Gen[(Long, Op)] = delay(Gen.alphaNumChar.map(Keystroke))
  val genBackspace: Gen[(Long, Op)] = delay(Gen.const(Backspace))

  // delays for simulated network latency and simulated keyboard WPM. So that checking 100 scenarios doesn't take
  // hours, the time scale is compressed.
  val genLatency: Gen[Long] = Gen.choose(8L, 12L)
  val genLatencies: Gen[Stream[Long]] = Gen.infiniteStream(genLatency)

  def delay[T](genKeypress: Gen[Op]): Gen[(Long, Op)] = for {
    delay <- Gen.choose(3, 5)
    op    <- genKeypress
  } yield (delay, op)

  def genKeypressesFor(offset: Int, n: Int): Gen[List[(Long, Op)]] = if (n == 0) Gen.const(List.empty) else offset match {
    case 0 => genKeypress.flatMap(op => genKeypressesFor(offset + 1, n - 1).map(rest => op :: rest))
    case _ => Gen.frequency(
      5 -> genKeypress.flatMap(op => genKeypressesFor(offset + 1, n - 1).map(rest => op :: rest)),
      5 -> genBackspace.flatMap(op => genKeypressesFor(offset - 1, n - 1).map(rest => op :: rest))
    )
  }

//  val genDelayedKeypress: Gen[(Long, Op)] = for {
//    delay    <- Gen.choose(30, 50)
//    keypress <- genKeypress
//  } yield (delay, keypress)
//
//  val genKeypresses: Gen[List[(Long, Op)]] = Gen.listOf(genDelayedKeypress)

//  implicit val arbKeypresses: Arbitrary[List[(Long, Op)]] = Arbitrary(genKeypresses)
//  private def smallerKeypresses(keypresses: List[(Long, Op)]): Stream[List[(Long, Op)]] = keypresses match {
//    case Nil       => Stream.empty
//    case _ :: Nil  => Stream.empty
//    case _ :: rest => rest #:: smallerKeypresses(rest)
//  }
//
//  implicit val shrinkKeypresses: Shrink[List[(Long, Op)]] = Shrink(smallerKeypresses)

  implicit val arbInit: Arbitrary[Init] = Arbitrary {
    Gen.sized {
      size => for {
        initialOffset <- Gen.choose(0, "text in cell".length)
        ops           <- genKeypressesFor(initialOffset, size)
        inbound       <- genLatencies
        outbound      <- genLatencies
      } yield Init(initialOffset, ops, inbound, outbound)
    }
  }

  implicit val shrinkInit: Shrink[Init] = {
    def shrink(init: Init): Stream[Init] = init match {
      case Init(_, Nil, _, _)      => Stream.empty
      // case Init(_, _ :: Nil, _, _) => init.copy(ops = Nil) #:: Stream.empty
      case Init(_, ops, _, _) =>
        val smaller = init.copy(ops = ops.take(ops.size - 1))
        smaller #:: shrink(smaller)
    }
    Shrink(shrink)
  }

}