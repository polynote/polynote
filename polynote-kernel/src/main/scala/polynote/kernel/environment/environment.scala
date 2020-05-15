package polynote.kernel.environment

import cats.effect.concurrent.Ref
import fs2.Stream
import fs2.concurrent.SignallingRef
import polynote.app.{Args, MainArgs}
import polynote.config.PolynoteConfig
import polynote.env.ops.Enrich
import polynote.kernel.interpreter.CellExecutor
import polynote.kernel.logging.Logging
import polynote.kernel.util.Publish
import polynote.kernel.{BaseEnv, CellEnv, ExecutionStatus, GlobalEnv, InterpreterEnv, KernelStatusUpdate, NotebookRef, Output, Result, TaskInfo}
import polynote.messages.{CellID, Message, Notebook, NotebookCell, NotebookConfig, NotebookUpdate}
import polynote.runtime.KernelRuntime
import zio.blocking.Blocking
import zio.clock.Clock
import zio.internal.Executor
import zio.interop.catz._
import zio.{Has, Layer, RIO, Tagged, Task, UIO, ULayer, URIO, ZIO, ZLayer, ZManaged}
//import zio.syntax.zioTuple3Syntax

//////////////////////////////////////////////////////////////////////
// Environment modules to mix for various layers of the application //
//////////////////////////////////////////////////////////////////////

/**
  * The capability to access the current configuration as a [[PolynoteConfig]]
  */

object Config {
  def access: URIO[Config, PolynoteConfig] = ZIO.access[Config](_.get)
  def of(config: PolynoteConfig): Config = Has(config)
  def layerOf(config: PolynoteConfig): Layer[Nothing, Config] = ZLayer.succeed(config)
  val layer: ZLayer[BaseEnv with MainArgs, Throwable, Config] = ZLayer.fromServiceM[Args, BaseEnv, Throwable, PolynoteConfig] {
    (args: Args) => PolynoteConfig.load(args.configFile)
  }
}

/**
  * The capability to publish status updates
  */
object PublishStatus {
  def access: RIO[PublishStatus, Publish[Task, KernelStatusUpdate]] = ZIO.access[PublishStatus](_.get)
  def apply(statusUpdate: KernelStatusUpdate): RIO[PublishStatus, Unit] =
   access.flatMap(_.publish1(statusUpdate))

  def layer(publishStatus: Publish[Task, KernelStatusUpdate]): ULayer[PublishStatus] = ZLayer.succeed(publishStatus)
}

/**
  * The capability to publish results
  */
object PublishResult {
  def access: RIO[PublishResult, Publish[Task, Result]] = ZIO.access[PublishResult](_.get)
  def apply(result: Result): RIO[PublishResult, Unit] =
    access.flatMap(_.publish1(result))

  def apply(results: List[Result]): RIO[PublishResult, Unit] =
    access.flatMap {
      pr => Stream.emits(results).through(pr.publish).compile.drain
    }

  def layer(publishResult: Publish[Task, Result]): ULayer[PublishResult] = ZLayer.succeed(publishResult)
  def ignore: ULayer[PublishResult] = layer(Publish.fn[Task, Result](_ => ZIO.unit))
}

/**
  * The capability to publish general messages
  */
object PublishMessage extends (Message => RIO[PublishMessage, Unit]) {
  def access: RIO[PublishMessage, Publish[Task, Message]] = ZIO.access[PublishMessage](_.get)
  def apply(message: Message): RIO[PublishMessage, Unit] =
    access.flatMap(_.publish1(message))

  def of(publish: Publish[Task, Message]): PublishMessage = Has(publish)
}

/**
  * The capability to access and update the current task as a [[TaskInfo]]
  */
object CurrentTask {
  def access: RIO[CurrentTask, Ref[Task, TaskInfo]] = ZIO.access[CurrentTask](_.get)
  def get: RIO[CurrentTask, TaskInfo] = access.flatMap(_.get)

  def update(fn: TaskInfo => TaskInfo): RIO[CurrentTask, Unit] = for {
    ref   <- access
    value <- ref.get
    _     <- if (fn(value) != value) ref.update(fn) else ZIO.unit
  } yield ()

  def of(ref: Ref[Task, TaskInfo]): CurrentTask = Has(ref)

  def layer(ref: Ref[Task, TaskInfo]): Layer[Nothing, CurrentTask] = ZLayer.succeed(ref)
  def none: Layer[Throwable, CurrentTask] = ZLayer.fromEffect(Ref[Task].of(TaskInfo("None")))
}

/**
  * The capability to access the current [[KernelRuntime]]
  */
object CurrentRuntime {
  object NoRuntime extends KernelRuntime(
    new KernelRuntime.Display {
      def content(contentType: String, content: String): Unit = ()
    },
    (_, _) => (),
    _ => ()
  )

  val noRuntime: Layer[Nothing, CurrentRuntime] = ZLayer.succeed(NoRuntime)

  def from(
    cellID: CellID,
    publishResult: Publish[Task, Result],
    publishStatus: Publish[Task, KernelStatusUpdate],
    taskRef: Ref[Task, TaskInfo]
  ): ZIO[Any, Throwable, KernelRuntime] =
    ZIO.runtime.map {
      runtime =>
        new KernelRuntime(
          new KernelRuntime.Display {
            def content(contentType: String, content: String): Unit = runtime.unsafeRunSync(publishResult.publish1(Output(contentType, content)))
          },
          (frac, detail) => runtime.unsafeRunAsync_(taskRef.tryUpdate(_.progress(frac, Option(detail).filter(_.nonEmpty)))),
          posOpt => runtime.unsafeRunAsync_(publishStatus.publish1(ExecutionStatus(cellID, posOpt.map(boxed => (boxed._1.intValue(), boxed._2.intValue())))))
        )
    }



  def layer(cellID: CellID): ZLayer[BaseEnv with GlobalEnv with CellEnv with CurrentTask, Throwable, CurrentRuntime] =
    ZLayer.fromEffect(ZIO.mapParN(PublishResult.access, PublishStatus.access, CurrentTask.access)(CurrentRuntime.from(cellID, _, _, _)).flatten)

  def access: ZIO[CurrentRuntime, Nothing, KernelRuntime] = ZIO.access[CurrentRuntime](_.get)
}

/**
  * The capability to access and modify the current [[Notebook]]
  */
// TODO: should separate out a read-only capability for interpreters (they have no business modifying the notebook)
object CurrentNotebook {
  def layer(ref: NotebookRef): ULayer[CurrentNotebook] = ZLayer.succeed(ref)
  def const(notebook: Notebook): ZLayer[Logging with Clock, Nothing, CurrentNotebook] = ZLayer.succeed(new NotebookRef.Const(notebook))
  def get: RIO[CurrentNotebook, Notebook] = getVersioned.map(_._2)
  def path: RIO[CurrentNotebook, String] = get.map(_.path)
  def getVersioned: RIO[CurrentNotebook, (Int, Notebook)] = ZIO.accessM[CurrentNotebook](_.get.getVersioned)

  def getCell(id: CellID): RIO[CurrentNotebook, NotebookCell] = get.flatMap {
    notebook => ZIO.fromOption(notebook.getCell(id)).mapError(_ => new NoSuchElementException(s"No such cell $id in notebook ${notebook.path}"))
  }

  def config: RIO[CurrentNotebook, NotebookConfig] = get.map(_.config.getOrElse(NotebookConfig.empty))
}

/**
  * The capability to access a stream of changes to the notebook's content
  */
object NotebookUpdates {
  def access: RIO[NotebookUpdates, Stream[Task, NotebookUpdate]] = ZIO.access[NotebookUpdates](_.get)
  def empty: ULayer[NotebookUpdates] = ZLayer.succeed[Stream[Task, NotebookUpdate]](Stream.empty)
}



/**
  * Some utilities for enrichment of environment
  */
object Env {

  /**
    * Add a new type to the environment inside a for-comprehension. This variant is for an environment that's constructed
    * effectfully.
    *
    * This method takes the "current" environment type, and enriches it with the given value. The remainder of the
    * for-comprehension can then access that environment. For example, instead of this:
    *
    * for {
    *   someValue <- computeThing
    *   myEnv1    <- Env.enrichM[Env1 with Env2](CreateMyEnv(someValue))
    *   thing1    <- doThing1.provide(myEnv1)
    *   thing2    <- doThing2.provide(myEnv1)
    *   // etc
    * } yield thing2
    *
    * You can do this:
    *
    * for {
    *   someValue <- computeThing
    *   _         <- Env.addM[Env1 with Env2](CreateMyEnv(someValue))
    *   thing1    <- doThing1
    *   thing2    <- doThing2
    * } yield thing2
    *
    * The MyEnv environment is automatically provided to the continuation after Env.addM, so it doesn't have to be
    * named and explicitly provided everywhere.
    */
  def addM[RO <: Has[_]]: AddMPartial[RO] = addMPartialInst.asInstanceOf[AddMPartial[RO]]

  class AddMPartial[RO <: Has[_]] {
    def apply[RA, E, RB](rbTask: ZIO[RA, E, RB]): AddM[RO, RA, RB, E] = new AddM(rbTask)
  }
  private val addMPartialInst: AddMPartial[Has[Any]] = new AddMPartial[Has[Any]]

  class AddM[RO <: Has[_], -RA, RB, +E](val rbTask: ZIO[RA, E, RB]) extends AnyVal {
    def flatMap[E1 >: E, A](zio: RB => ZIO[RO with Has[RB], E1, A])(implicit ev: Tagged[RB], ev1: Tagged[Has[RB]]): ZIO[RO with RA, E1, A] =
      ZLayer.fromEffect(rbTask).build.use(r => zio(r.get[RB]).provideSomeLayer[RO](ZLayer.succeed(r.get[RB])))
  }

  /**
    * Add a new type to the environment inside a for-comprehension. This variant is for an environment that's constructed
    * directly.
    *
    * This method takes the "current" environment type, and enriches it with the given value. The remainder of the
    * for-comprehension can then access that environment. For example, instead of this:
    *
    * for {
    *   someValue <- computeThing
    *   myEnv1    <- Env.enrich[Env1 with Env2](CreateMyEnv(someValue))
    *   thing1    <- doThing1.provide(myEnv1)
    *   thing2    <- doThing2.provide(myEnv1)
    *   // etc
    * } yield thing2
    *
    * You can do this:
    *
    * for {
    *   someValue <- computeThing
    *   _         <- Env.add[Env1 with Env2](CreateMyEnv(someValue))
    *   thing1    <- doThing1
    *   thing2    <- doThing2
    * } yield thing2
    *
    * The MyEnv environment is automatically provided to the continuation after Env.add, so it doesn't have to be
    * named and explicitly provided everywhere.
    */
  def add[RO <: Has[_]]: AddPartial[RO] = addPartialInstance.asInstanceOf[AddPartial[RO]]

  class AddPartial[RO <: Has[_]] {
    def apply[R](r: R): Add[RO, R] = new Add[RO, R](r)
  }
  private val addPartialInstance: AddPartial[Has[Any]] = new AddPartial[Has[Any]]

  class Add[RO <: Has[_], R](val r: R) extends AnyVal {
    def flatMap[E, A](zio: R => ZIO[RO with Has[R], E, A])(implicit ev: Tagged[Has[R]], ev1: Tagged[R]): ZIO[RO, E, A] =
      zio(r).provideSomeLayer[RO](ZLayer.succeed(r))
  }

  def addMany[RO <: Has[_]]: AddManyPartial[RO] = addManyPartialInstance.asInstanceOf[AddManyPartial[RO]]

  class AddManyPartial[RO <: Has[_]] {
    def apply[R <: Has[_]](r: R): AddMany[RO, R] = new AddMany[RO, R](r)
  }
  private val addManyPartialInstance: AddManyPartial[Has[Any]] = new AddManyPartial[Has[Any]]

  class AddMany[RO <: Has[_], R <: Has[_]](val r: R) extends AnyVal {
    def flatMap[E, A](zio: R => ZIO[RO with R, E, A])(implicit ev: Tagged[R]): ZIO[RO, E, A] =
      zio(r).provideSomeLayer[RO](ZLayer.succeedMany(r))
  }

  def addLayer[RO <: Has[_], E, R <: Has[_]](layer: ZLayer[RO, E, R]): AddLayer[RO, E, R] =
    new AddLayer(layer)

  class AddLayer[RO <: Has[_], E, R <: Has[_]](val layer: ZLayer[RO, E, R]) extends AnyVal {
    def flatMap[E1 >: E, A](zio: R => ZIO[RO with R, E1, A])(implicit ev: Tagged[R]): ZIO[RO, E1, A] =
      ZIO.environment[R].flatMap(r => zio(r)).provideSomeLayer[RO](layer)
  }

  def addManagedLayer[RO <: Has[_], E, R <: Has[_]](layer: ZLayer[RO, E, R]): AddManagedLayer[RO, E, R] =
    new AddManagedLayer(layer)

  class AddManagedLayer[RO <: Has[_], E, R <: Has[_]](val layer: ZLayer[RO, E, R]) extends AnyVal {
    def flatMap[E1 >: E, A](zio: R => ZManaged[RO with R, E1, A])(implicit ev: Tagged[R]): ZManaged[RO, E1, A] =
      ZManaged.environment[R].flatMap(r => zio(r)).provideSomeLayer[RO](layer)
  }

  implicit class LayerOps[RIn, E, ROut <: Has[_]](val self: ZLayer[RIn, E, ROut]) extends AnyVal {
    def andThen[E1 >: E, RIn1 <: Has[_], ROut1 <: Has[_]](
      next: ZLayer[ROut with RIn1, E1, ROut1]
    )(implicit ev: Tagged[ROut], ev1: Tagged[ROut1], ev2: Tagged[RIn1]): ZLayer[RIn1 with RIn, E1, ROut with ROut1] = {
      val next1: ZLayer[RIn with RIn1, E1, ROut1] = (self ++ ZLayer.identity[RIn1]) >>> next
      self.zipWithPar[E1, RIn with RIn1, ROut, ROut1, ROut with ROut1](next1) {
        (rOut, rOut1) => rOut.union[ROut1](rOut1)
      }
    }
  }



}