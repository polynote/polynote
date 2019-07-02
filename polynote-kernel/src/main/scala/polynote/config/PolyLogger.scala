package polynote.config

/**
  * Yeah I know another Logger class. Well, we need to log stuff too. And Spark is a total jerk and hijacks all the
  * logging and we don't want to deal with it. So, we're doing this and it'll work and it won't be a big pain in the apricot.
  */
class PolyLogger(debug: Boolean) {
  private val outStream = System.err

  def info(msg: String): Unit = outStream.println(msg)
  def error(err: Throwable)(msg: String): Unit = {
    info(msg)
    err.printStackTrace(outStream)
  }
  def error(msg: String): Unit = info(msg)

  def debug(msg: String): Unit = if (debug) info(msg)
  def debug(err: Throwable)(msg: String): Unit = if (debug) error(err)(msg)
}
