package polynote.kernel.remote

import java.net.InetSocketAddress

import cats.effect.concurrent.Ref
import org.scalamock.scalatest.MockFactory
import polynote.config.PolynoteConfig
import polynote.kernel.{BaseEnv, Kernel, Output, TaskInfo, UpdatedTasks}
import polynote.kernel.Kernel.Factory
import polynote.kernel.environment.{Config, PublishResult, PublishStatus}
import polynote.kernel.interpreter.Interpreter
import polynote.kernel.networking.Networking
import polynote.messages.{Notebook, TinyList}
import polynote.testing.ZIOSpecBase
import polynote.testing.ZIOSpecBase.SpecBaseEnv
import polynote.testing.kernel.remote.InProcessDeploy
import polynote.testing.kernel.{MockEnv, MockKernelEnv}
import zio.blocking.Blocking
import zio.{Has, Task, URIO, ZIO, ZLayer}
import zio.test.{assert => zassert, _}
import zio.test.Assertion._
import zio.duration._
import RemoteKernelSpecBase1.Environment

object RemoteKernelSpecBase1 {
  type Environment = MockEnv.Env with Has[MockKernelEnv] with Has[RemoteKernel[InetSocketAddress]]
}

/**
  * TODO: this is a WIP effort to start migrating to ZIO test. But, I'm waiting for the IntelliJ runner to be updated. --jeremys
  */
abstract class RemoteKernelSpecBase1 extends RunnableSpec[Environment, Throwable] with MockFactory {
  protected def config: PolynoteConfig
  protected def label: String

  protected val kernel        = mock[Kernel]
  protected val kernelFactory = Factory.const(kernel)
  protected val deploy        = new InProcessDeploy(kernelFactory)
  protected val transport     = new SocketTransport(deploy)

  protected def remoteKernel: URIO[Environment, RemoteKernel[InetSocketAddress]] =
    ZIO.access[Has[RemoteKernel[InetSocketAddress]]](_.get)

  protected def mockEnv: URIO[Environment, MockKernelEnv] =
    ZIO.access[Has[MockKernelEnv]](_.get)

  protected def assertEnv[A](get: MockKernelEnv => A)(assertion: Assertion[A]): URIO[RemoteKernelSpecBase1.Environment, TestResult] = assertM(mockEnv.map(get))(assertion)

  private final val mockKernelEnvLayer = ZLayer.fromEffect(MockKernelEnv(kernelFactory, config).orDie)

  private final val mockEnvLayer: ZLayer[Has[MockKernelEnv] with BaseEnv, Nothing, MockEnv.ExtEnv] = ZLayer.fromServiceMany {
    ke: MockKernelEnv => ke.extHas
  }

  private final val envLayer: ZLayer[Any, Nothing, Has[MockKernelEnv] with MockEnv.Env] =
    ZIOSpecBase.baseLayer >>> ((ZLayer.identity[BaseEnv] >>> mockKernelEnvLayer.passthrough) >>> mockEnvLayer.passthrough).passthrough

  private final val remoteKernelLayer = envLayer >>> ZLayer.fromManaged(RemoteKernel(transport)).orDie

  override def aspects: List[TestAspect[Nothing, Environment, Nothing, Any]] = Nil

  override def runner: TestRunner[Environment, Throwable] = TestRunner(TestExecutor.default(envLayer ++ remoteKernelLayer ++ Annotations.live))

  override def spec: ZSpec[Environment, Throwable] = suite("RemoteKernel")(
    suite("With real networking")(
      testM("init") {
        val statusUpdate = UpdatedTasks(TinyList.of(TaskInfo("init task")))
        val result = Output("text/plain", "some predef result")
        (kernel.init _).expects().returning {
          PublishResult(result) *> PublishStatus(statusUpdate)
        }

        for {
          remoteKernel  <- remoteKernel
          statusUpdates <- mockEnv.flatMap(_.publishStatus.toList)
          results       <- mockEnv.flatMap(_.publishResult.toList)
        } yield zassert(statusUpdates)(contains(statusUpdate)) && zassert(results)(contains(result))
      }
    )
  )
}
