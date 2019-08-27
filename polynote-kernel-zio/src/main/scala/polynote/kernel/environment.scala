package polynote.kernel

import java.lang.reflect.{InvocationHandler, Method, Proxy}

import cats.effect.concurrent.Ref
import polynote.config.PolynoteConfig
import polynote.kernel.interpreter.{CellExecutor, Interpreter}
import polynote.kernel.util.Publish
import polynote.messages.{CellID, Notebook}
import polynote.runtime.KernelRuntime
import zio.{Task, TaskR, UIO, ZIO}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.internal.Executor
import zio.system.System
import zio.syntax._
import zio.interop.catz._

import scala.reflect.{ClassTag, classTag}

//////////////////////////////////////////////////////////////////////
// Environment modules to mix for various layers of the application //
//////////////////////////////////////////////////////////////////////

trait PublishStatus {
  val publishStatus: Publish[Task, KernelStatusUpdate]
}

object PublishStatus {
  def access: TaskR[PublishStatus, Publish[Task, KernelStatusUpdate]] = ZIO.access[PublishStatus](_.publishStatus)
  def apply(statusUpdate: KernelStatusUpdate): TaskR[PublishStatus, Unit] =
    ZIO.accessM[PublishStatus](_.publishStatus.publish1(statusUpdate))
}

trait PublishResults {
  val publishResult: Publish[Task, Result]
}

object PublishResults {
  def access: TaskR[PublishResults, Publish[Task, Result]] = ZIO.access[PublishResults](_.publishResult)
  def apply(result: Result): TaskR[PublishResults, Unit] =
    ZIO.accessM[PublishResults](_.publishResult.publish1(result))
}

trait CurrentTask {
  val currentTask: Ref[Task, TaskInfo]
}

object CurrentTask {
  def access: TaskR[CurrentTask, Ref[Task, TaskInfo]] = ZIO.access[CurrentTask](_.currentTask)
}

trait CurrentRuntime {
  val currentRuntime: KernelRuntime
}

object CurrentRuntime {
  object NoRuntime extends KernelRuntime(
    new KernelRuntime.Display {
      def content(contentType: String, content: String): Unit = ()
    },
    (_, _) => (),
    _ => ()
  )

  object NoCurrentRuntime extends CurrentRuntime {
    val currentRuntime: KernelRuntime = NoRuntime
  }

  def from(
    cellID: CellID,
    publishResult: Publish[Task, Result],
    publishStatus: Publish[Task, KernelStatusUpdate],
    taskRef: Ref[Task, TaskInfo]
  ): Task[CurrentRuntime] = ZIO.runtime.map {
    runtime =>
      new CurrentRuntime {
        val currentRuntime: KernelRuntime = new KernelRuntime(
          new KernelRuntime.Display {
            def content(contentType: String, content: String): Unit = runtime.unsafeRunSync(publishResult.publish1(Output(contentType, content)))
          },
          (frac, detail) => runtime.unsafeRunAsync_(taskRef.tryUpdate(_.progress(frac, Option(detail).filter(_.nonEmpty)))),
          posOpt => runtime.unsafeRunAsync_(publishStatus.publish1(ExecutionStatus(cellID, posOpt.map(boxed => (boxed._1.intValue(), boxed._2.intValue())))))
        )
      }
  }

  def from(cellID: CellID): TaskR[PublishResults with PublishStatus with CurrentTask, CurrentRuntime] =
    ((PublishResults.access, PublishStatus.access, CurrentTask.access)).map3(CurrentRuntime.from(cellID, _, _, _)).flatten

  def access: TaskR[CurrentRuntime, KernelRuntime] = ZIO.access[CurrentRuntime](_.currentRuntime)
}

trait CurrentNotebook {
  val currentNotebook: Ref[Task, Notebook]
}

object CurrentNotebook {
  def access: TaskR[CurrentNotebook, Ref[Task, Notebook]] = ZIO.access[CurrentNotebook](_.currentNotebook)
  def get: TaskR[CurrentNotebook, Notebook] = ZIO.accessM[CurrentNotebook](_.currentNotebook.get)
  def update(fn: Notebook => Notebook): TaskR[CurrentNotebook, Notebook] = ZIO.accessM[CurrentNotebook] {
    cn => cn.currentNotebook.modify(notebook => fn(notebook) match { case nb => (nb, nb) })
  }
}

/////////////////////////////////////////////////////////////////////////////////////////////
// Some concrete environment classes to make it easier to instantiate composed env modules //
/////////////////////////////////////////////////////////////////////////////////////////////
class BaseEnvironment(
  baseEnv: BaseEnv
) extends BaseEnvT {
  val blocking: Blocking.Service[Any] = baseEnv.blocking
  val clock: Clock.Service[Any] = baseEnv.clock
  val system: System.Service[Any] = baseEnv.system
}

class KernelConfig(
  baseEnv: BaseEnv,
  val currentNotebook: Ref[Task, Notebook],
  val scalaCompiler: ScalaCompiler,
  val interpreterFactories: Map[String, Interpreter.Factory],
  val config: PolynoteConfig
) extends BaseEnvironment(baseEnv) with KernelConfigEnvT {
  def withTaskManager(taskManager: TaskManager[KernelEnv]): KernelFactoryEnvironment =
    new KernelFactoryEnvironment(currentNotebook, scalaCompiler, interpreterFactories, config, taskManager)
}

object KernelConfig {
  def from(config: KernelConfigEnv): KernelConfig = config match {
    case config: KernelConfig => config
    case config => new KernelConfig(config.currentNotebook, config.scalaCompiler, config.interpreterFactories, config.config)
  }
}

class KernelFactoryEnvironment(
  baseEnv: BaseEnv,
  currentNotebook: Ref[Task, Notebook],
  scalaCompiler: ScalaCompiler,
  interpreterFactories: Map[String, Interpreter.Factory],
  config: PolynoteConfig,
  val taskManager: TaskManager[KernelEnv]
) extends KernelConfig(baseEnv, currentNotebook, scalaCompiler, interpreterFactories, config) with KernelFactoryEnvT

class KernelEnvironment(
  baseEnv: BaseEnv,
  val publishStatus: Publish[Task, KernelStatusUpdate],
  val publishResult: Publish[Task, Result]
) extends KernelEnvT {
  override val blocking: Blocking.Service[Any] = baseEnv.blocking
  override val clock: Clock.Service[Any] = baseEnv.clock
  override val system: System.Service[Any] = baseEnv.system
}

object KernelEnvironment {
  def from(env: KernelEnv): KernelEnvironment = env match {
    case env: KernelEnvironment => env
    case env => new KernelEnvironment(env, env.publishStatus, env.publishResult)
  }
}

class KernelTaskEnvironment(
  baseEnv: BaseEnv,
  publishStatus: Publish[Task, KernelStatusUpdate],
  publishResult: Publish[Task, Result],
  val currentTask: Ref[Task, TaskInfo]
) extends KernelEnvironment(baseEnv, publishStatus, publishResult) with KernelTaskEnvT

object KernelTaskEnvironment {
  def from(env: KernelTaskEnv): KernelTaskEnvironment = env match {
    case env: KernelTaskEnvironment => env
    case env => new KernelTaskEnvironment(env, env.publishStatus, env.publishResult, env.currentTask)
  }

  def from(kernelEnv: KernelEnv, currentTask: Ref[Task, TaskInfo]): KernelTaskEnvironment =
    new KernelTaskEnvironment(kernelEnv, kernelEnv.publishStatus, kernelEnv.publishResult, currentTask)
}

case class CellEnvironment(
  blocking: Blocking.Service[Any],
  clock: Clock.Service[Any],
  system: System.Service[Any],
  publishResult: Publish[Task, Result],
  publishStatus: Publish[Task, KernelStatusUpdate],
  currentTask: Ref[Task, TaskInfo],
  currentRuntime: KernelRuntime
) extends CellEnvT {

  def localBlocking(mk: Executor => Executor): CellEnvironment = copy(
    blocking = new Blocking.Service[Any] {
      override def blockingExecutor: ZIO[Any, Nothing, Executor] = CellEnvironment.this.blocking.blockingExecutor.map(mk)
    }
  )

  def tapResults(to: Result => Task[Unit]): CellEnvironment = copy(
    publishResult = publishResult.tap(to)
  )

  /**
    * Insert custom [[Blocking]] service.
    * @see [[CellExecutor]]
    */
  def mkExecutor(classLoader: ClassLoader): UIO[CellEnvironment] = ZIO.runtime.map {
    runtime => localBlocking(new CellExecutor(result => runtime.unsafeRun(publishResult.publish1(result)), classLoader, _))
  }
}

object CellEnvironment {
  def from(baseEnv: BaseEnv, publishResult: Publish[Task, Result], publishStatus: Publish[Task, KernelStatusUpdate], currentTask: Ref[Task, TaskInfo], currentRuntime: KernelRuntime): CellEnvironment =
    CellEnvironment(baseEnv.blocking, baseEnv.clock, baseEnv.system, publishResult, publishStatus, currentTask, currentRuntime)

  def from(kernelEnv: KernelEnv, currentTask: Ref[Task, TaskInfo], currentRuntime: KernelRuntime): CellEnvironment =
    from(kernelEnv, kernelEnv.publishResult, kernelEnv.publishStatus, currentTask, currentRuntime)

  def from(env: CellEnv): CellEnvironment = env match {
    case env: CellEnvironment => env
    case env => CellEnvironment(env.blocking, env.clock, env.system, env.publishResult, env.publishStatus, env.currentTask, env.currentRuntime)
  }

  def fromKernel(cellID: CellID): TaskR[KernelEnv with CurrentTask, CellEnvironment] = for {
    env         <- ZIO.access[KernelEnv](identity)
    currentTask <- CurrentTask.access
    currentRuntime <- CurrentRuntime.from(cellID)
  } yield from(env, currentTask, currentRuntime.currentRuntime)
}

trait Provider[A] {
  type Out
  def apply(provider: Out): A
}

object Provider {
  import shapeless._
  import shapeless.ops.hlist._

  type Aux[A, Out0] = Provider[A] { type Out = Out0 }

  def instance[A, Out0](provide: Out0 => A): Aux[A, Out0] = new Provider[A] {
    type Out = Out0
    def apply(provider: Out0): A = provide(provider)
  }

  implicit val blocking: Aux[Blocking.Service[Any], Blocking] = instance(_.blocking)
  implicit val clock: Aux[Clock.Service[Any], Clock] = instance(_.clock)
  implicit val system: Aux[System.Service[Any], System] = instance(_.system)

  implicit val hnil: Aux[HNil, Any] = instance(_ => HNil)
  implicit def hcons[H, HP, T <: HList, TP](implicit
    providerH: Aux[H, HP],
    providerT: Aux[T, TP]
  ): Aux[H :: T, HP with TP] = instance {
    headWithTail => providerH(headWithTail) :: providerT(headWithTail)
  }

  implicit def product[P <: Product, L <: HList, LProvider](implicit
    gen: Generic.Aux[P, L],
    providerL: Provider.Aux[L, LProvider]
  ): Aux[P, LProvider] = instance(pl => gen.from(providerL(pl)))

  def access[T](implicit provider: Provider[T]): ZIO[provider.Out, Nothing, T] = ZIO.access[provider.Out](env => provider(env))

  implicit class HListOps[R <: HList, E, A](val self: ZIO[R, E, A]) extends AnyVal {

    /**
      * Eliminate one type from the environment HList by providing it.
      * @param  t  The environment element to provide
      * @return A new [[ZIO]] requiring the remaining environment
      */
    def provideOne[T, Remainder <: HList](t: T)(implicit
      remove: Remove.Aux[R, T, (T, Remainder)]
    ): ZIO[Remainder, E, A] = self.provideSome[Remainder](remainder => remove.reinsert((t, remainder)))

    /**
      * Eliminate two types from the environment HList by providing them
      * @param  t1  The first environment element to provide
      * @param  t2  The second environment element to provide
      * @return A [[ZIO]] requiring the remaining environment
      */
    def provideMany[T1, T2, Remainder <: HList](t1: T1, t2: T2)(implicit
      remove: RemoveAll.Aux[R, T1 :: T2 :: HNil, (T1 :: T2 :: HNil, Remainder)]
    ): ZIO[Remainder, E, A] = provideL(t1 :: t2 :: HNil)

    def provideMany[T1, T2, T3, Remainder <: HList](t1: T1, t2: T2, t3: T3)(implicit
      remove: RemoveAll.Aux[R, T1 :: T2 :: T3 :: HNil, (T1 :: T2 :: T3 :: HNil, Remainder)]
    ): ZIO[Remainder, E, A] = provideL(t1 :: t2 :: t3 :: HNil)

    def provideL[L <: HList, Remainder <: HList](l: L)(implicit
      remove: RemoveAll.Aux[R, L, (L, Remainder)]
    ): ZIO[Remainder, E, A] = self.provideSome[Remainder](remainder => remove.reinsert((l, remainder)))
  }
}

/**
  * Some utilities for dynamic enrichment of environment – it uses Java proxies so it's not ideal, but hopefully
  * it can be replaced by a better solution and works for now.
  */
object Env {

  def enrich[A : ClassTag, B : ClassTag](a: A, b: B): A with B = {
    val aInterfaces = classTag[A].runtimeClass.getInterfaces
    val bInterfaces = classTag[B].runtimeClass.getInterfaces
    val allInterfaces = (aInterfaces ++ bInterfaces).distinct

    def accessorsOf(interfaces: Array[Class[_]]): Map[String, Method] = interfaces.flatMap {
      i => i.getDeclaredMethods.map {
        method => method.getName -> method
      }
    }.toMap

    val aMethods = accessorsOf(aInterfaces).mapValues(Left(_))
    val bMethods = accessorsOf(bInterfaces).mapValues(Right(_))
    val allMethods = (aMethods ++ bMethods).map {
      case (_, methodE) => methodE.fold(identity, identity) -> methodE
    }

    val invocationHandler = new InvocationHandler {
      def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef =
        allMethods.getOrElse(method, throw new NoSuchMethodError(method.toString)) match {
          case Left(m)  => m.invoke(a, args)
          case Right(m) => m.invoke(b, args)
        }
    }

    Proxy.newProxyInstance(getClass.getClassLoader, allInterfaces, invocationHandler).asInstanceOf[A with B]
  }

  def enrichM[A : ClassTag, B : ClassTag](b: B): ZIO[A, Nothing, A with B] = ZIO.access[A](identity).map(enrich[A, B](_, b))

}