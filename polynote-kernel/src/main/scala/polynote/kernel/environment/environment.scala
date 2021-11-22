package polynote.kernel.environment

import polynote.app.{Args, MainArgs}
import polynote.config.PolynoteConfig
import polynote.kernel.logging.Logging
import polynote.kernel.util.{Publish, UPublish}
import polynote.kernel.{BaseEnv, CellEnv, Complete, ExecutionStatus, GlobalEnv, InterpreterEnv, KernelStatusUpdate, NotebookRef, Output, Result, TaskInfo}
import polynote.messages.{CellID, Message, Notebook, NotebookCell, NotebookConfig, NotebookUpdate}
import polynote.runtime.KernelRuntime
import zio.clock.Clock
import zio.{Has, Layer, RIO, RefM, Tag, Task, UIO, ULayer, URIO, ZIO, ZLayer, ZManaged}

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
  def access: RIO[PublishStatus, UPublish[KernelStatusUpdate]] = ZIO.access[PublishStatus](_.get)
  def apply(statusUpdate: KernelStatusUpdate): RIO[PublishStatus, Unit] =
   access.flatMap(_.publish(statusUpdate))

  def layer(publishStatus: UPublish[KernelStatusUpdate]): ULayer[PublishStatus] = ZLayer.succeed(publishStatus)
}

/**
  * The capability to publish results
  */
object PublishResult {
  def access: RIO[PublishResult, UPublish[Result]] = ZIO.access[PublishResult](_.get)
  def apply(result: Result): RIO[PublishResult, Unit] =
    access.flatMap(_.publish(result))

  def apply(results: List[Result]): RIO[PublishResult, Unit] =
    access.flatMap {
      pr => ZIO.foreach_(results)(pr.publish)
    }

  def layer(publishResult: UPublish[Result]): ULayer[PublishResult] = ZLayer.succeed(publishResult)
  def ignore: ULayer[PublishResult] = layer(Publish.ignore)
}

/**
  * The capability to publish general messages
  */
object PublishMessage extends (Message => RIO[PublishMessage, Unit]) {
  def access: RIO[PublishMessage, UPublish[Message]] = ZIO.access[PublishMessage](_.get)
  def apply(message: Message): RIO[PublishMessage, Unit] =
    access.flatMap(_.publish(message))

  def of(publish: UPublish[Message]): PublishMessage = Has(publish)
  def ignore: PublishMessage = Has[UPublish[Message]](Publish.ignore)
}

/**
  * The capability to access and update the current task as a [[TaskInfo]]
  */
object CurrentTask {
  def access: URIO[CurrentTask, TaskRef] = ZIO.access[CurrentTask](_.get)
  def get: URIO[CurrentTask, TaskInfo] = access.flatMap(_.get)

  def update(fn: TaskInfo => TaskInfo): URIO[CurrentTask, Unit] = for {
    ref      <- access
    _        <- ref.update { task =>
        val next = fn(task)
        if (next != task) ZIO.succeed(task) else ZIO.fail(())
    }.ignore
  } yield ()

  def update(detail: String = "", progress: Double = Double.NaN): RIO[CurrentTask, Unit] =
    update {
      taskInfo =>
        val progressByte = if (progress.isNaN) taskInfo.progress else Math.round(progress * 255).toByte
        val status = if (progressByte == 255) Complete else taskInfo.status
        taskInfo.copy(detail = detail, progress = progressByte, status = status)
    }

  def setProgress(progress: Byte): RIO[CurrentTask, Unit] = update(_.copy(progress = progress))
  def setProgress(progress: Double): RIO[CurrentTask, Unit] = setProgress(Math.round(progress * 255).toByte)

  //def of(ref: Ref[Task, TaskInfo]): CurrentTask = Has(ref)

  def layer(ref: TaskRef): Layer[Nothing, CurrentTask] = ZLayer.succeed(ref)
  def none: Layer[Throwable, CurrentTask] = ZLayer.fromEffect(RefM.make(TaskInfo("None")))
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
    publishResult: UPublish[Result],
    publishStatus: UPublish[KernelStatusUpdate],
    taskRef: TaskRef
  ): ZIO[Any, Throwable, KernelRuntime] =
    ZIO.runtime.map {
      runtime =>
        new KernelRuntime(
          new KernelRuntime.Display {
            def content(contentType: String, content: String): Unit = runtime.unsafeRunSync(publishResult.publish(Output(contentType, content)))
          },
          (frac, detail) => runtime.unsafeRunAsync_(taskRef.update(t => ZIO.succeed(t.progress(frac, Option(detail).filter(_.nonEmpty))))),
          posOpt => runtime.unsafeRunAsync_(publishStatus.publish(ExecutionStatus(cellID, posOpt.map(boxed => (boxed._1.intValue(), boxed._2.intValue())))))
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
  def access: URIO[CurrentNotebook, NotebookRef] = ZIO.access[CurrentNotebook](_.get)
  def get: URIO[CurrentNotebook, Notebook] = getVersioned.map(_._2)
  def path: URIO[CurrentNotebook, String] = get.map(_.path)
  def getVersioned: URIO[CurrentNotebook, (Int, Notebook)] = access.flatMap(_.getVersioned)

  def getCell(id: CellID): RIO[CurrentNotebook, NotebookCell] = get.flatMap {
    notebook => ZIO.fromOption(notebook.getCell(id)).orElseFail(new NoSuchElementException(s"No such cell $id in notebook ${notebook.path}"))
  }

  def config: URIO[CurrentNotebook, NotebookConfig] = get.map(_.config.getOrElse(NotebookConfig.empty))
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
    def flatMap[E1 >: E, A](zio: RB => ZIO[RO with Has[RB], E1, A])(implicit ev: Tag[RB], ev1: Tag[Has[RB]]): ZIO[RO with RA, E1, A] =
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
    def flatMap[E, A](zio: R => ZIO[RO with Has[R], E, A])(implicit ev: Tag[Has[R]], ev1: Tag[R]): ZIO[RO, E, A] =
      zio(r).provideSomeLayer[RO](ZLayer.succeed(r))
  }

  def addManaged[R0 <: Has[_]]: AddManagedPartial[R0] = addManagedPartialInstance.asInstanceOf[AddManagedPartial[R0]]

  class AddManagedPartial[RO <: Has[_]] {
    def apply[R](r: R): AddManaged[RO, R] = new AddManaged[RO, R](r)
  }
  private val addManagedPartialInstance: AddManagedPartial[Has[Any]] = new AddManagedPartial[Has[Any]]

  class AddManaged[RO <: Has[_], R](val r: R) extends AnyVal {
    def flatMap[E, A](managed: R => ZManaged[RO with Has[R], E, A])(implicit ev: Tag[Has[R]], ev1: Tag[R]): ZManaged[RO, E, A] =
      managed(r).provideSomeLayer[RO](ZLayer.succeed(r))
  }

  def addMany[RO <: Has[_]]: AddManyPartial[RO] = addManyPartialInstance.asInstanceOf[AddManyPartial[RO]]

  class AddManyPartial[RO <: Has[_]] {
    def apply[R <: Has[_]](r: R): AddMany[RO, R] = new AddMany[RO, R](r)
  }
  private val addManyPartialInstance: AddManyPartial[Has[Any]] = new AddManyPartial[Has[Any]]

  class AddMany[RO <: Has[_], R <: Has[_]](val r: R) extends AnyVal {
    def flatMap[E, A](zio: R => ZIO[RO with R, E, A])(implicit ev: Tag[R]): ZIO[RO, E, A] =
      zio(r).provideSomeLayer[RO](ZLayer.succeedMany(r))
  }

  def addLayer[RO <: Has[_], E, R <: Has[_]](layer: ZLayer[RO, E, R]): AddLayer[RO, E, R] =
    new AddLayer(layer)

  class AddLayer[RO <: Has[_], E, R <: Has[_]](val layer: ZLayer[RO, E, R]) extends AnyVal {
    def flatMap[E1 >: E, A](zio: R => ZIO[RO with R, E1, A])(implicit ev: Tag[R]): ZIO[RO, E1, A] =
      ZIO.environment[R].flatMap(r => zio(r)).provideSomeLayer[RO](layer)
  }

  def addManagedLayer[RO <: Has[_], E, R <: Has[_]](layer: ZLayer[RO, E, R]): AddManagedLayer[RO, E, R] =
    new AddManagedLayer(layer)

  class AddManagedLayer[RO <: Has[_], E, R <: Has[_]](val layer: ZLayer[RO, E, R]) extends AnyVal {
    def flatMap[E1 >: E, A](zio: R => ZManaged[RO with R, E1, A])(implicit ev: Tag[R]): ZManaged[RO, E1, A] =
      ZManaged.environment[R].flatMap(r => zio(r)).provideSomeLayer[RO](layer)
  }

  implicit class LayerOps[-RIn, +E, ROut <: Has[_]](val self: ZLayer[RIn, E, ROut]) extends AnyVal {
    def andThen[E1 >: E, RIn1 <: Has[_], ROut1 <: Has[_]](
      next: ZLayer[ROut with RIn1, E1, ROut1]
    )(implicit ev: Tag[ROut], ev1: Tag[ROut1], ev2: Tag[RIn1]): ZLayer[RIn1 with RIn, E1, ROut with ROut1] = {
      val next1: ZLayer[RIn with RIn1, E1, ROut1] = (self ++ ZLayer.identity[RIn1]) >>> next
      self.zipWithPar[E1, RIn with RIn1, ROut, ROut1, ROut with ROut1](next1) {
        (rOut, rOut1) => rOut.union[ROut1](rOut1)
      }
    }
  }



}