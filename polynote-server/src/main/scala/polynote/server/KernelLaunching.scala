package polynote.server

import cats.effect.{ContextShift, IO, Timer}
import polynote.kernel.dependency.{CoursierFetcher, DependencyFetcher, IvyFetcher}


trait KernelLaunching {

  protected implicit def timer: Timer[IO]
  protected implicit def contextShift: ContextShift[IO]

  protected val dependencyFetcher = new IvyFetcher() //new CoursierFetcher()
  protected val dependencyFetchers: Map[String, DependencyFetcher[IO]] = Map("scala" -> dependencyFetcher)

  protected def kernelFactory: KernelFactory[IO] = new IOKernelFactory(dependencyFetchers)

}
