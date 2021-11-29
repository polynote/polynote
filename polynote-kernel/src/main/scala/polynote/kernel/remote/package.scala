package polynote.kernel

import polynote.kernel.util.RPublish
import polynote.messages.NotebookConfig
import zio.Has

import java.io.File

package object remote {

  type PublishRemoteResponse = Has[RPublish[BaseEnv, RemoteResponse]]

  lazy val javaOptions: Map[String, String] = {
    val libraryPath = List(sys.props.get("java.library.path"), sys.env.get("LD_LIBRARY_PATH"))
      .flatten
      .mkString(File.pathSeparator)

    Map(
      "log4j.configuration" -> "log4j.properties",
      "java.library.path"   -> libraryPath
    )
  }

  def asPropString(m: Map[String, String]): List[String] = m.toList.map {
    case (name, value) => s"-D$name=$value"
  }

  def jvmArgs(nbConfig: NotebookConfig) = nbConfig.jvmArgs.toList.flatten
}
