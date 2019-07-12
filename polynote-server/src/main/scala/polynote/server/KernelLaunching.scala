package polynote.server

import cats.effect.{ContextShift, IO, Timer}
import polynote.kernel.dependency.{CoursierFetcher, DependencyManagerFactory}
import polynote.kernel.lang.python.VirtualEnvManager


trait KernelLaunching {

  protected implicit def timer: Timer[IO]
  protected implicit def contextShift: ContextShift[IO]

  protected val scalaDep = CoursierFetcher.Factory
  protected val pythonDep = VirtualEnvManager.Factory
  protected val dependencyManagers: Map[String, DependencyManagerFactory[IO]] = Map(
    "scala" -> scalaDep,
    "python" -> pythonDep
  )

  protected def kernelFactory: KernelFactory[IO] = new IOKernelFactory(dependencyManagers)

}
