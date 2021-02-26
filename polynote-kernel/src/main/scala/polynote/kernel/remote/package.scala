package polynote.kernel

import polynote.kernel.util.Publish
import zio.{Has, Task}

import java.io.File

package object remote {

  type PublishRemoteResponse = Has[Publish[Task, RemoteResponse]]

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
}
