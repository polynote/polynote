package polynote.kernel
package remote

import java.util.concurrent.TimeUnit

import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FreeSpec, Matchers}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import polynote.kernel.Kernel.Factory
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
import zio.{RIO, Ref, Task, ZIO}
import zio.interop.catz._

import scala.concurrent.TimeoutException

class RemoteKernelSpec extends FreeSpec with Matchers with ZIOSpec with BeforeAndAfterAll with BeforeAndAfterEach with MockFactory with ScalaCheckDrivenPropertyChecks {
  private val kernel        = mock[Kernel]
  private val kernelFactory = new Factory.LocalService {
    def apply(): RIO[BaseEnv with GlobalEnv with CellEnv, Kernel] = ZIO.succeed(kernel)
  }

  private val env           = unsafeRun(MockKernelEnv(kernelFactory))
  private val clientRef     = unsafeRun(Ref.make[RemoteKernelClient](null))
  private val deploy        = new InProcessDeploy(kernelFactory, clientRef)
  private val transport     = new SocketTransport(deploy, Some("127.0.0.1"))
  private val remoteKernel  = unsafeRun(RemoteKernel(transport).provide(env))

  "RemoteKernel" - {

    "with real networking" - {

      "init" in {
        val statusUpdate = UpdatedTasks(TinyList.of(TaskInfo("init task")))
        val result = Output("text/plain", "some predef result")

        (kernel.init _).expects().returning {
          PublishResult(result) *> PublishStatus(statusUpdate)
        }

        unsafeRun(remoteKernel.init().provide(env))
        unsafeRun(env.publishStatus.toList) should contain(statusUpdate)
        unsafeRun(env.publishResult.toList) shouldEqual List(result)
      }

      "queueCell" in {
        (kernel.queueCell _).expects(CellID(1)).returning {
          PublishResult(Output("text/plain", "hello")).as(ZIO.unit)
        }
        unsafeRun(remoteKernel.queueCell(CellID(1)).provide(env).flatten)
        unsafeRun(env.publishResult.toList) shouldEqual List(Output("text/plain", "hello"))
      }

      "completionsAt" in {
        val completion = Completion("foo", Nil, TinyList.of(TinyList.of(("test", "thing"))), "resultType", CompletionType.Method)
        (kernel.completionsAt _).expects(CellID(1), 5).returning(ZIO.succeed(List(completion)))
        unsafeRun(remoteKernel.completionsAt(CellID(1), 5).provide(env)) shouldEqual List(completion)
      }

      "parametersAt" in {
        val params = Signatures(TinyList.of(ParameterHints("name", None, TinyList.of(ParameterHint("name", "typeName", None)))), 0, 1)
        (kernel.parametersAt _).expects(CellID(1), 5).returning(ZIO.succeed(Some(params)))
        unsafeRun(remoteKernel.parametersAt(CellID(1), 5).provide(env)) shouldEqual Some(params)
      }

      "status" in {
        val status = KernelBusyState(busy = false, alive = true)
        (kernel.status _).expects().returning(ZIO.succeed(status))
        unsafeRun(remoteKernel.status()) shouldEqual status
      }

      "info" in {
        val info = KernelInfo("foo" -> "bar", "baz" -> "buzz")
        (kernel.info _).expects().returning(ZIO.succeed(info))
        unsafeRun(remoteKernel.info().provide(env)) shouldEqual info
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
        unsafeRun(remoteKernel.getHandleData(Streaming, 0, 1).provide(env)).toList match {
          case one :: Nil => one shouldEqual data
          case other => fail(other.toString)
        }
      }

      "modifyStream" in {
        val ops = List(GroupAgg(List("one", "two"), List(("agg", "bagg"))))
        val newHandle = StreamingDataRepr(1, StringType, Some(2))
        (kernel.modifyStream _).expects(0, ops).returning(ZIO.succeed(Some(newHandle)))
        unsafeRun(remoteKernel.modifyStream(0, ops).provide(env)) shouldEqual Some(newHandle)
      }

      "releaseHandle" in {
        (kernel.releaseHandle _).expects(Streaming, 1).returning(ZIO.unit)
        unsafeRun(remoteKernel.releaseHandle(Streaming, 1).provide(env))
      }

      "handles notebook updates" in {
        forAll((Generators.genNotebookUpdates _).tupled(unsafeRun(env.currentNotebook.get)), MinSize(4)) {
          case (finalNotebook, updates) =>
            whenever(updates.nonEmpty) {
              val finalVersion = updates.last.globalVersion
              updates.foreach {
                update => unsafeRun(env.updateTopic.publish1(Some(update)))
              }

              val (remoteVersion, remoteNotebook) = unsafeRun {
                clientRef.get.absorb.flatMap {
                  client => client.notebookRef.discrete.terminateAfter(_._1 == finalVersion).compile[Task, Task, (Int, Notebook)].lastOrError
                }.timeoutFail(new TimeoutException("timed out waiting for the correct notebook"))(zio.duration.Duration(2, TimeUnit.SECONDS))
              }
              remoteNotebook shouldEqual finalNotebook
            }

            unsafeRun(clientRef.get.flatMap(_.notebookRef.set(unsafeRun(env.currentNotebook.get))))
        }
      }

      "shutdown" in {
        (kernel.shutdown _).expects().returning(ZIO.unit)
        val remoteExit = unsafeRunSync(remoteKernel.shutdown())
        remoteExit.fold(
          err => fail(s"Shutdown failed or was interrupted:\n${err.prettyPrint}"),
          _ => ()
        )
      }
    }

  }

  override def afterEach(): Unit = {
    env.publishResult.reset()
    env.publishStatus.reset()
  }
}
