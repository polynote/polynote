package polynote.server

object SparkServer extends Server {
  override protected val router = new SparkPolyKernelRouter(
    Map("scala" -> dependencyFetcher), subKernels
  )
}
