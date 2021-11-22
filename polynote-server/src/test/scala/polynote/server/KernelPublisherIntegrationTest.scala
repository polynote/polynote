package polynote.server

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import org.scalacheck.{Arbitrary, Gen, Shrink}
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
import polynote.kernel.{BaseEnv, CellEnv, GlobalEnv, Kernel, KernelBusyState, KernelError, KernelInfo, KernelStatusUpdate, LocalKernel, LocalKernelFactory, Output}
import polynote.messages.{CellID, ContentEdit, ContentEdits, Delete, Insert, Message, Notebook, NotebookCell, NotebookConfig, NotebookUpdate, ShortList, UpdateCell}
import polynote.server.auth.UserIdentity
import polynote.testing.ExtConfiguredZIOSpec
import polynote.testing.kernel.MockNotebookRef
import zio.clock.Clock
import zio.duration.Duration
import zio.stream.{Take, ZStream, ZTransducer}
import zio.{Fiber, Promise, Queue, RIO, Ref, Semaphore, Tag, Task, UIO, ULayer, URIO, ZIO, ZLayer, ZManaged, random}
import random.Random

import scala.collection.mutable.ListBuffer
import KernelPublisherIntegrationTest._
import polynote.util.VersionBuffer

import java.util.concurrent.atomic.AtomicInteger
import scala.util.control.NonFatal

class KernelPublisherIntegrationTest extends FreeSpec with Matchers with ExtConfiguredZIOSpec[Interpreter.Factories] with MockFactory with ScalaCheckDrivenPropertyChecks {
  val tagged: Tag[Interpreter.Factories] = implicitly

  override lazy val configuredEnvLayer: ZLayer[zio.ZEnv with Config, Nothing, Interpreter.Factories] = ZLayer.succeed(Map.empty)

  private def mkStubKernel = {
    val stubKernel = stub[Kernel]
    (stubKernel.shutdown _).when().returns(ZIO.unit)
    (() => stubKernel.awaitClosed).when().returns(ZIO.unit)
    (stubKernel.init _).when().returns(ZIO.unit)
    (stubKernel.info _).when().returns(ZIO.succeed(KernelInfo()))
    stubKernel
  }

  private val bq = Publish.ignore[Message]

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSize = 10, sizeRange = 10, minSuccessful = 50)

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
      val collectStatus   = kernelPublisher.subscribeStatus.runCollect.map(_.toList).forkDaemon.runIO()
      val kernel          = kernelPublisher.kernel.runWith(kernelFactory).asInstanceOf[RemoteKernel[InetSocketAddress]]
      val process         = kernel.transport.asInstanceOf[SocketTransportServer].process

      process.kill().runIO()
      assert(process.awaitExit(1, TimeUnit.SECONDS).runIO().nonEmpty, "first process exited")

      val kernel2 = kernelPublisher.kernel
        .repeatUntil(_ ne kernel)
        .timeout(Duration(20, TimeUnit.SECONDS))
        .someOrFail(new Exception("Kernel should have changed; didn't change after 20 seconds"))
        .runWith(kernelFactory)

      assert(kernel2 ne kernel, "Kernel should have changed")

      val process2 = kernel.transport.asInstanceOf[SocketTransportServer].process

      kernelPublisher
        .subscribeStatus
        .takeUntil(_ == KernelBusyState(false, true))
        .timeout(Duration(20, TimeUnit.SECONDS)).runDrain.runIO()

      kernelPublisher.close().runIO()

      assert(process2.awaitExit(1, TimeUnit.SECONDS).runIO().nonEmpty, "second process exited")
      // TODO: test that it exited with 0. Currently it doesn't, it is killed. Why?

      val statuses = collectStatus.join.runIO()

      assert(statuses.exists(_.isInstanceOf[KernelError]), "Should have seen a kernel error")
      assert(statuses.contains(KernelBusyState(false, false)), "Should have seen a dead kernel")
    }

    "gracefully handles startup failure of kernel" in {
      val stubKernel = mkStubKernel

      case class FailedToStart() extends Exception("The kernel fails to start. What do you do?")

      val failingKernelFactory: Factory.Service = new Factory.Service {
        private var attempted = 0
        override def apply(): RIO[BaseEnv with GlobalEnv with CellEnv, Kernel] =
          ZIO(attempted).bracket(n => ZIO.effectTotal { attempted = n + 1 }) {
            case 0 => ZIO.fail(FailedToStart())
            case n => ZIO.succeed(stubKernel)
          }
      }

      val notebook        = Notebook("/i/am/fake.ipynb", ShortList(Nil), None)
      val ref             = MockNotebookRef(notebook).runIO()
      val kernelPublisher = KernelPublisher(ref, bq).runWith(failingKernelFactory)
      val stopStatus = Promise.make[Throwable, Unit].runIO()
      val collectStatus = kernelPublisher.subscribeStatus
        .haltWhen(stopStatus.await.either)
        .runCollect.map(_.toList)
        .forkDaemon.runIO()

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
    
    "handles concurrent edits" - {
      def check(client1Init: Init, client2Init: Init) = {
        // start up a KernelPublisher and two subscribers. Then, simulate keyboard-mashing from each subscriber
        // and track their local state using the same logic as the front-end. At the end, the server and both
        // clients should have the same state.

        val kernel          = mkStubKernel
        val kernelFactory   = Kernel.Factory.const(kernel)
        val cell = NotebookCell(
          CellID(0), "scala",
          initialText
        )
        val notebook        = Notebook("/i/am/fake.ipynb", ShortList(List(cell)), None)
        val ref             = MockNotebookRef(notebook).runIO()
        val serverLog = new ListBuffer[(Long, String)]
        val kernelPublisher = KernelPublisher(ref, bq, serverLog).runWith(kernelFactory)

        val client1 = new Client(Ref.make(notebook).runIO(), kernelPublisher, kernelFactory, client1Init)
        val client2 = new Client(Ref.make(notebook).runIO(), kernelPublisher, kernelFactory, client2Init)

        // listen to the canonical updates from the publisher
        val serverEdits = new ListBuffer[(Int, NotebookUpdate, Long)]
        val stopListening = Promise.make[Nothing, Unit].runIO()
        val listener = kernelPublisher.subscribeUpdates.use {
          _.mapM {
            edit => zio.clock.currentTime(TimeUnit.MILLISECONDS).flatMap {
              time => ZIO.effectTotal(serverEdits += ((edit._1, edit._2, time)))
            }
          }.haltWhen(stopListening).runDrain
        }.forkDaemon.runIO()

        val expected1 = client1Init.ops.length
        val expected2 = client2Init.ops.length
        val maxRemaining = ZIO.mapN(
          client1.receivedCount.map(expected2 - _),
          client2.receivedCount.map(expected1 - _)
        )(math.max)

        val result = (client1.sendReceive <&> client2.sendReceive).use {
          case ((send1, receive1), (send2, receive2)) =>
            for {
                _    <- send1.join
                _    <- send2.join
                _    <- maxRemaining.repeatUntilEquals(0)
                _    <- client1.inbound.offer(Take.end)
                _    <- client2.inbound.offer(Take.end)
                _    <- receive1.join
                _    <- receive2.join
                res1 <- client1.results
                res2 <- client2.results
                serv <- kernelPublisher.versionedNotebook.get.map(_.cells.head.content.toString)
                _    <- stopListening.succeed(())
                _    <- kernelPublisher.close()
                _    <- listener.join
            } yield (res1, res2, serv)
        }


        try {
          def showLogs(): Unit = {
            (client1.log ++ client2.log ++ serverLog).sortBy(_._1).foreach {
              case (t, log) => println(log)
            }

            println("\n===LOGS===")
            println(s"${client1.outLog.size} ${client2.inLog.size} ${client2.outLog.size} ${client1.inLog.size}")
            val c1Out = client1.outLog.iterator
            val c2In  = client2.inLog.iterator
            val c2Out = client2.outLog.iterator
            val c1In  = client1.inLog.iterator

            while (c1Out.hasNext || c2In.hasNext || c2Out.hasNext || c1In.hasNext) {
              val out1 = if (c1Out.hasNext) c1Out.next() else ""
              val in2  = if (c2In.hasNext) c2In.next() else ""
              val out2 = if (c2Out.hasNext) c2Out.next() else ""
              val in1  = if (c1In.hasNext) c1In.next() else ""
              println(s"< 0: $out1")
              println(s"< 1: $out2")
              println(s"> 0: $in1")
              println(s"> 1: $in2")
              println()
            }
          }

          val ((res1, edits1), (res2, edits2), serv) = try {
            (random.setSeed(0L) *> result).runWith(envLayer)
          } catch {
            case NonFatal(err) =>
              println("===ERROR===")
              showLogs()
              throw err
          }

          if (res1 != serv || res2 != serv) {
            println(s"0: $res1")
            println(s"1: $res2")
            println(s"S: $serv")
            println("===SHRINK===")
            showLogs()
          } else {
          }
          res1 shouldEqual serv
          res2 shouldEqual serv
        } finally {
          //kernelPublisher.close().runIO()
        }
      }

      "repeatedly adding and deleting in the same place" in {
        val ops1 = List.fill(10)(List((scala.util.Random.nextInt(2).toLong + 3,Keystroke("e")), (scala.util.Random.nextInt(2).toLong + 3,Backspace))).flatten
        val ops2 = List.fill(10)(List((scala.util.Random.nextInt(2).toLong + 3,Keystroke("u")), (scala.util.Random.nextInt(2).toLong + 3,Backspace))).flatten
        def lats = List.fill(math.max(ops1.size, ops2.size))(scala.util.Random.nextInt(8).toLong + 4)
        check(
          Init(18, ops1, lats, lats),
          Init(18, ops2, lats, lats)
        )
      }
      
      "check" in forAll("Client 1 keystrokes", "Client 2 keystrokes") {
        (client1Init: Init, client2Init: Init) => check(client1Init, client2Init)
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
    private val numReceived = new AtomicInteger(0)
    private val versionBuffer = new ClientUpdateBuffer()
    private val localVersionRef = Ref.make(0).runIO()
    private val globalVersionRef = Ref.make(0).runIO()
    private val currentOffset = Ref.make(init.initialOffset).runIO()
    private val Init(_, keypresses, inboundLatencies, outboundLatencies) = init

    private val outbound = ZStream(keypresses: _*)

    // a semaphore to simulate JavaScript single-threadedness
    private val js = Semaphore.make(1L).runIO()

    private val publishEnv: ULayer[PublishMessage] = ZLayer.succeed(inbound)
    private val identityEnv: ULayer[UserIdentity] = ZLayer.succeed(None)
    val env: ZLayer[Any, Nothing, Kernel.Factory with PublishMessage with UserIdentity] = ZLayer.succeed(kernelFactory) ++ publishEnv ++ identityEnv

    private val edits = new ListBuffer[ContentEdit]
    private val allUpdates = new ListBuffer[ContentEdits]
    val log = new ListBuffer[(Long, String)]
    val outLog = new ListBuffer[String]
    val inLog = new ListBuffer[String]
    val globalVersions = new ListBuffer[(Int, String)]

    def receivedCount: UIO[Int] = ZIO.effectTotal(numReceived.get())

    def content: String = notebook.get.runIO().cells.head.content.toString

    def results: UIO[(String, List[ContentEdits])] = notebook.get.map(_.cells.head.content.toString -> allUpdates.toList)

    val sendReceive: ZManaged[BaseEnv with Config with Interpreter.Factories, Throwable, (Fiber[Nothing, Unit], Fiber[Nothing, Unit])] = publisher.subscribe().flatMap {
      subscriber => sendTo(subscriber).forkManaged <&> receiveFrom(subscriber).forkManaged
    }.provideSomeLayer[BaseEnv with Config with Interpreter.Factories](env)

    private def receiveFrom(subscriber: KernelSubscriber): URIO[Random with Clock, Unit] = {
      // receive messages (with simulated latency) from the server and try to process them in the same fashion as the client
      val latencies = inboundLatencies.iterator
      val nextLatency = ZIO(latencies.next()).orDie.map(Duration.fromMillis)
      val simulateLatency = nextLatency.flatMap(l => ZIO.sleep(l))

      ZStream.fromQueue(inbound).flattenTake.mapM {
        case update: NotebookUpdate =>
          val logStr = new StringBuilder
          simulateLatency *> js.withPermit {
            notebook.get.flatMap { prev =>
              ZIO.effectTotal(logStr ++= s"""> ${subscriber.id} $update "${prev.cells.head.content}"\n""") *>
              localVersionRef.get.flatMap {
                localVersion =>
                  val currentTime = System.currentTimeMillis()
                  val rebased = if (update.localVersion < localVersion) {
                    logStr ++= s"""  rebasing $update from L${update.localVersion} to L$localVersion on "${prev.cells.head.content}"\n"""
                    versionBuffer.rebaseThrough(update, localVersion)
                  } else {
                    update
                  }
                  inLog += (rebased match {
                    case UpdateCell(_, _, _, edits, _) if edits.edits.nonEmpty =>
                      val editLog = new ListBuffer[String]
                      edits.edits.foldLeft(prev.cells.head.content) {
                        (content, edit) =>
                          editLog += edit.action(content.toString)
                          edit.applyTo(content)
                      }
                      editLog.mkString("\n     ")

                    case UpdateCell(_, _, _, edits, _) =>
                      val editLog = new ListBuffer[String]
                      editLog += "[Eliminated:]"
                      update.asInstanceOf[UpdateCell].edits.edits.foldLeft(prev.cells.head.content) {
                        (content, edit) =>
                          editLog += edit.action(content.toString)
                          edit.applyTo(content)
                      }
                      editLog.mkString("\n     ")
                  })
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
                              case (currentOffset, Delete(pos, length)) if pos < currentOffset => currentOffset - length
                              case (currentOffset, _) => currentOffset
                            }
                          case _ => currentOffset
                        }
                    }
              }
            }.unit
          }.ensuring(ZIO.effectTotal(numReceived.incrementAndGet()))

        case _ => ZIO.unit
      }.runDrain
    }

    private def sendTo(subscriber: KernelSubscriber): URIO[Random with Clock, Unit] = {
      val latencies = outboundLatencies.iterator

      val doBackspace = currentOffset.get.flatMap {
        case i if i <= 0 => ZIO.fail(())
        case i           => currentOffset.updateAndGet(_ - 1).map(offs => Delete(offs, 1))
      }

      val nextLatency = ZIO(latencies.next()).map(Duration.fromMillis)
      val simulateLatency = nextLatency.flatMap(l => ZIO.sleep(l))

      val sendKeypresses = outbound.foreach {
        case (delay, op) =>
          ZIO.sleep(Duration.fromMillis(delay)).as(op).flatMap {
            case Backspace       => doBackspace
            case Keystroke(char) => currentOffset.getAndUpdate(_ + 1).map(i => Insert(i, char.toString))
          }.flatMap {
            edit =>
              js.withPermit {
                for {
                  prevContent <- notebook.get.map(_.cells.head.content.toString)
                  localVersion <- localVersionRef.updateAndGet(_ + 1)
                  globalVersion <- globalVersionRef.get
                  _ = edits += edit
                  ce = ContentEdits(edit)
                  _ = allUpdates += ce
                  update = UpdateCell(globalVersion, localVersion, CellID(0), ce, None)
                  nb <- notebook.updateAndGet(update.applyTo)
                  _ = versionBuffer.add(localVersion, update)
                  content = nb.cells.head.content.toString
                  str = s"""< ${subscriber.id} $update "$prevContent" -> "$content""""
                  _ = log += ((System.currentTimeMillis(), str))
                  _ = outLog += edit.action(prevContent)
                } yield update
              }
          }.flatMap {
            update => simulateLatency.orDie *> subscriber.update(update)
          }.foldCauseM(
            failure => ZIO.effectTotal(System.err.println(s"Send error: $failure")),
            _       => ZIO.unit
          )
      }

      sendKeypresses
    }
  }

}

object KernelPublisherIntegrationTest {

  val initialText = "ABCDEFHIJKLMNOPQRSTUVWXYZ"

  sealed trait Op
  final case object Backspace extends Op
  final case class Keystroke(char: String) extends Op
  final case class HighlightAndDelete(pos: Int, len: Int) extends Op
  final case class HighlightAndPaste(pos: Int, len: Int, str: String) extends Op

  case class Init(
    initialOffset: Int,
    ops: List[(Long, Op)],
    inboundLatencies: Seq[Long],
    outboundLatencies: Seq[Long]
  )

  val genKeypress: Gen[(Long, Keystroke)] = delay(Gen.alphaLowerChar.map(_.toString).map(Keystroke))
  val genBackspace: Gen[(Long, Op)] = delay(Gen.const(Backspace))
  val genPaste: Gen[(Long, Keystroke)] = delay(Gen.alphaLowerStr.map(Keystroke))
  def genDelete(len: Int): Gen[(Long, HighlightAndDelete)] = delay {
    for {
      a <- Gen.choose(0, len - 1)
      b <- Gen.choose(0, len - 1)
      if a != b
    } yield HighlightAndDelete(math.min(a, b), math.max(a, b))
  }

  // delays for simulated network latency and simulated keyboard WPM. So that checking 100 scenarios doesn't take
  // hours, the time scale is compressed.
  val genLatency: Gen[Long] = Gen.choose(8L, 12L)
  val genLatencies: Gen[Stream[Long]] = Gen.infiniteStream(genLatency)

  def delay[T <: Op](genOp: Gen[T]): Gen[(Long, T)] = for {
    delay <- Gen.choose(3, 5)
    op    <- genOp
  } yield (delay, op)

  def genKeypressesFor(offset: Int, n: Int): Gen[List[(Long, Op)]] = if (n == 0) Gen.const(List.empty) else offset match {
    case 0 => genKeypress.flatMap(op => genKeypressesFor(offset + op._2.char.length, n - 1).map(rest => op :: rest))
    case _ => Gen.frequency(
      9 -> genKeypress.flatMap(op => genKeypressesFor(offset + 1, n - 1).map(rest => op :: rest)),
      2 -> genBackspace.flatMap(op => genKeypressesFor(offset - 1, n - 1).map(rest => op :: rest))
    )
  }

  final case class Wait(i: Int) extends AnyVal
  implicit val arbWait: Arbitrary[Wait] = Arbitrary {
    Gen.choose(10, 500).map(i => Wait(i))
  }

  implicit val shrinkWait: Shrink[Wait] = {
    def shrink(wait: Wait): Stream[Wait] = wait match {
      case Wait(i) if i >= 500 => Stream.empty
      case Wait(i) =>
        val next = Wait(math.min(500, math.ceil(i * 2).toInt))
        next #:: shrink(next)
    }
    Shrink(shrink)
  }

  implicit val arbInit: Arbitrary[Init] = Arbitrary {
    Gen.sized {
      size => for {
        initialOffset <- Gen.choose(0, initialText.length)
        ops           <- genKeypressesFor(initialOffset, size)
        inbound       <- genLatencies
        outbound      <- genLatencies
      } yield Init(initialOffset, ops, inbound.take(ops.size).toList, outbound.take(ops.size).toList)
    }
  }

  implicit val shrinkInit: Shrink[Init] = {
    def shrink(init: Init): Stream[Init] = init match {
      case Init(_, Nil, _, _)      => Stream.empty
      case Init(_, ops, _, _) =>
        val smaller = init.copy(ops = ops.take(ops.size - 1))
        smaller #:: shrink(smaller)
    }
    Shrink(shrink)
  }

  /**
    * A simulation of the update buffer on the client.
    * TODO: the client code should be updated to do this as well – rebaseThrough and update the buffer with the leftovers from the rebase
    */
  class ClientUpdateBuffer extends VersionBuffer[NotebookUpdate] {

    /**
      * Rebase the update through the given version, excluding updates from the given subscriber. Each version that's
      * rebased through will also be mutated to account for the given update with respect to future updates which
      * go through that version.
      * @return
      */
    def rebaseThrough(update: NotebookUpdate, targetVersion: Int, log: Option[StringBuilder] = None, updateBuffer: Boolean = true): NotebookUpdate = update match {
      case update@UpdateCell(_, sourceVersion, cellId, sourceEdits, _) =>
        synchronized {
          var index = versionIndex(sourceVersion + 1)
          if (index < 0) {
            log.foreach(_ ++= s"  No version ${sourceVersion + 1}")
            return update
          };

          val size = numVersions
          var currentVersion = versionedValueAt(index)._1
          var rebasedEdits = sourceEdits
          try {
            if (!(currentVersion <= targetVersion && index < size)) {
              log.foreach(_ ++= s"  No updates")
            }
            while (currentVersion <= targetVersion && index < size) {
              val elem = versionedValueAt(index)
              currentVersion = elem._1
              val prevUpdate= elem._2
              prevUpdate match {
                case prevUpdate@UpdateCell(_, _, `cellId`, targetEdits, _) =>
                  val (sourceRebased, targetRebased) = rebasedEdits.rebaseBoth(targetEdits)
                  rebasedEdits = sourceRebased
                  log.foreach(_ ++= s"  $prevUpdate => $sourceRebased\n")
                  if (updateBuffer) {
                    setValueAt(index, prevUpdate.copy(edits = ContentEdits(targetRebased)))
                  }
                case _ =>
              }
              index += 1
            }
          } catch {
            case err: Throwable => err.printStackTrace()
          }
          update.copy(edits = rebasedEdits)
        }
      case update => getRange(update.localVersion + 1, targetVersion).foldLeft(update) {
        case (accum, next) => accum.rebase(next)
      }
    }

  }

}