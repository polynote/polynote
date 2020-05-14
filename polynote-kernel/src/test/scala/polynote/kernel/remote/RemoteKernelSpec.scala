package polynote.kernel
package remote

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FreeSpec, Matchers}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import polynote.config.{KernelConfig, PolynoteConfig}
import polynote.kernel.Kernel.Factory
import polynote.kernel.RuntimeError.RecoveredException
import polynote.kernel.environment.{NotebookUpdates, PublishResult, PublishStatus}
import polynote.kernel.{BaseEnv, CellEnv, Completion, CompletionType, GlobalEnv, Kernel, KernelBusyState, KernelInfo, Output, ParameterHint, ParameterHints, ResultValue, Signatures, TaskInfo, UpdatedTasks}
import polynote.kernel.logging.Logging
import polynote.messages._
import polynote.runtime.ReprsOf.DataReprsOf
import polynote.runtime.{DataEncoder, GroupAgg, MIMERepr, StreamingDataRepr, StringType}
import polynote.testing.{Generators, ZIOSpec}
import polynote.testing.kernel.{MockEnv, MockKernelEnv}
import polynote.testing.kernel.remote.InProcessDeploy
import scodec.bits.ByteVector
import zio.blocking.effectBlocking
import zio.duration.Duration
import zio.{RIO, Ref, Task, ZIO}
import zio.interop.catz._
import zio.stream.ZStream

import scala.concurrent.TimeoutException

// Base to test remote kernel with various configurations
abstract class RemoteKernelSpecBase extends FreeSpec with Matchers with ZIOSpec with BeforeAndAfterEach with MockFactory with ScalaCheckDrivenPropertyChecks {
  import runtime.{unsafeRun, unsafeRunSync, unsafeRunTask}

  protected def config: PolynoteConfig
  protected def label: String

  protected val kernel        = mock[Kernel]
  protected val kernelFactory = new Factory.LocalService {
    def apply(): RIO[BaseEnv with GlobalEnv with CellEnv, Kernel] = ZIO.succeed(kernel)
  }

  protected val env           = unsafeRun(MockKernelEnv(kernelFactory, config))
  protected val clientRef     = unsafeRun(Ref.make[RemoteKernelClient](null))
  protected val deploy        = new InProcessDeploy(kernelFactory, clientRef)
  protected val transport     = new SocketTransport(deploy)
  protected val remoteKernel  = unsafeRun(RemoteKernel(transport).provideCustomLayer(env.baseLayer))

  s"RemoteKernel ($label)" - {

    "with real networking" - {

      "init" in {
        val statusUpdate = UpdatedTasks(TinyList.of(TaskInfo("init task")))
        val result = Output("text/plain", "some predef result")

        (kernel.init _).expects().returning {
          PublishResult(result) *> PublishStatus(statusUpdate)
        }

        unsafeRun(remoteKernel.init().provideCustomLayer(env.baseLayer))
        unsafeRun(env.publishStatus.toList) should contain(statusUpdate)
        unsafeRun(env.publishResult.toList) shouldEqual List(result)
      }

      "queueCell" in {
        (kernel.queueCell _).expects(CellID(1)).returning {
          PublishResult(Output("text/plain", "hello")).as(ZIO.unit)
        }
        unsafeRun(remoteKernel.queueCell(CellID(1)).provideCustomLayer(env.baseLayer).flatten)
        unsafeRun(env.publishResult.toList) shouldEqual List(Output("text/plain", "hello"))
      }

      "completionsAt" in {
        val completion = Completion("foo", Nil, TinyList.of(TinyList.of(("test", "thing"))), "resultType", CompletionType.Method)
        (kernel.completionsAt _).expects(CellID(1), 5).returning(ZIO.succeed(List(completion)))
        unsafeRun(remoteKernel.completionsAt(CellID(1), 5).provideCustomLayer(env.baseLayer)) shouldEqual List(completion)
      }

      "parametersAt" in {
        val params = Signatures(TinyList.of(ParameterHints("name", None, TinyList.of(ParameterHint("name", "typeName", None)))), 0, 1)
        (kernel.parametersAt _).expects(CellID(1), 5).returning(ZIO.succeed(Some(params)))
        unsafeRun(remoteKernel.parametersAt(CellID(1), 5).provideCustomLayer(env.baseLayer)) shouldEqual Some(params)
      }

      "status" in {
        val status = KernelBusyState(busy = false, alive = true)
        (kernel.status _).expects().returning(ZIO.succeed(status))
        unsafeRun(remoteKernel.status()) shouldEqual status
      }

      "info" in {
        val info = KernelInfo("foo" -> "bar", "baz" -> "buzz")
        (kernel.info _).expects().returning(ZIO.succeed(info))
        unsafeRun(remoteKernel.info().provideCustomLayer(env.baseLayer)) shouldEqual info
      }

      "values" in {
        import scala.reflect.runtime.universe.typeOf
        val value = ResultValue("name", "typeName", TinyList.of(MIMERepr("text/plain", "foo")), CellID(1), "foo", typeOf[String], Some((1,5)))
        (kernel.values _).expects().returning(ZIO.succeed(List(value)))
        unsafeRun(remoteKernel.values()) shouldEqual List(
          ResultValue("name", "typeName", TinyList.of(MIMERepr("text/plain", "foo")), CellID(1), (), scala.reflect.runtime.universe.NoType, Some((1,5)))
        )
      }

      "getHandleData" in {
        val data = ByteVector32(ByteVector(DataReprsOf.string.encode("testing")))
        (kernel.getHandleData _).expects(Streaming, 0, 1).returning(ZIO.succeed(Array(data)))
        unsafeRun(remoteKernel.getHandleData(Streaming, 0, 1).provideCustomLayer(env.baseLayer)).toList match {
          case one :: Nil => one shouldEqual data
          case other => fail(other.toString)
        }
      }

      "modifyStream" in {
        val ops = List(GroupAgg(List("one", "two"), List(("agg", "bagg"))))
        val newHandle = StreamingDataRepr(1, StringType, Some(2))
        (kernel.modifyStream _).expects(0, ops).returning(ZIO.succeed(Some(newHandle)))
        unsafeRun(remoteKernel.modifyStream(0, ops).provideCustomLayer(env.baseLayer)) shouldEqual Some(newHandle)
      }

      "releaseHandle" in {
        (kernel.releaseHandle _).expects(Streaming, 1).returning(ZIO.unit)
        unsafeRun(remoteKernel.releaseHandle(Streaming, 1).provideCustomLayer(env.baseLayer))
      }

      "handles notebook updates" in {
        val initial = unsafeRun(env.currentNotebook.getVersioned)
        forAll((Generators.genNotebookUpdates _).tupled(initial), MinSize(4)) {
          case (finalNotebook, updates) =>
            unsafeRun(env.currentNotebook.set(initial))
            unsafeRun(clientRef.get.flatMap(_.notebookRef.set(initial)))

            whenever(updates.nonEmpty) {
              val finalVersion = updates.last.globalVersion
              updates.foreach {
                update => unsafeRun(env.updateTopic.publish1(Some(update)))
              }

              val remoteNotebook = unsafeRun {
                clientRef.get.absorb.flatMap {
                  client => client.notebookRef.getVersioned.doUntil(_._1 == finalVersion)
                }.timeoutFail(new TimeoutException("timed out waiting for the correct notebook"))(zio.duration.Duration(2, TimeUnit.SECONDS))
              }
              remoteNotebook._2 shouldEqual finalNotebook
            }


        }
      }

      "handles errors" in {
        (kernel.info _).expects().returning(ZIO.fail(new RuntimeException("Simulated error")))
        a[RecoveredException] should be thrownBy {
          // unsafeRun throws a fiber failure; this way will throw the actual error
          unsafeRunSync(remoteKernel.info().provideSomeLayer(env.baseLayer)).fold(err => throw err.squash, identity)
        }
      }

      "shutdown" in {
        (kernel.shutdown _).expects().returning(ZIO.unit)
        unsafeRunTask(remoteKernel.shutdown())
      }
    }

  }

  override def afterEach(): Unit = {
    env.publishResult.reset()
    env.publishStatus.reset()
  }
}

class RemoteKernelSpec extends RemoteKernelSpecBase {
  override protected lazy val config: PolynoteConfig = PolynoteConfig()
  override protected lazy val label: String = "No config"
}

class RemoteKernelSpecWithPortRange extends RemoteKernelSpecBase {
  import runtime.{unsafeRun, unsafeRunSync, unsafeRunTask}
  override protected lazy val config: PolynoteConfig = PolynoteConfig(kernel = KernelConfig(portRange = Some(9000 to 10000)))
  override protected lazy val label: String = "With port range"

  "Gets port in correct range" in {
    unsafeRun {
      transport.openServerChannel.bracket(channel => ZIO.effectTotal(channel.close())) {
        channel => ZIO.effect {
          val port = channel.getLocalAddress.asInstanceOf[InetSocketAddress].getPort
          assert(port >= 9000 && port <= 10000)
        }
      }.provideSomeLayer(env.baseLayer)
    }
  }

  "Gets multiple ports in correct range" in {
    val uniquePorts = new scala.collection.mutable.HashSet[Int]()
    val numPorts = 5
    unsafeRun {
      ZIO.foreachPar(0 until numPorts) { _ =>
        transport.openServerChannel.bracket(channel => ZIO.effectTotal(channel.close())) {
          channel =>
            ZIO.effect(channel.getLocalAddress.asInstanceOf[InetSocketAddress].getPort).flatMap {
              port => effectBlocking {
                uniquePorts.synchronized {
                  uniquePorts += port
                }
            }
          } &> ZIO.sleep(Duration(1, TimeUnit.SECONDS))
        }
      }.provideSomeLayer(env.baseLayer)
    }

    assert(uniquePorts.size == numPorts)
    uniquePorts.foreach {
      port => assert(port >= 9000 && port <= 10000)
    }
  }
}