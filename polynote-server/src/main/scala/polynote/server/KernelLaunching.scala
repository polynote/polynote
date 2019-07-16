package polynote.server

import cats.effect.{ContextShift, IO, Timer}


trait KernelLaunching {

  protected implicit def timer: Timer[IO]
  protected implicit def contextShift: ContextShift[IO]

  protected def kernelFactory: KernelFactory[IO] = new IOKernelFactory()

}
