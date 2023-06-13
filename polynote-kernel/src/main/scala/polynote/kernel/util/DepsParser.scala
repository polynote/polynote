package polynote.kernel.util

import zio.{RIO, ZIO}
import zio.blocking.Blocking

import java.net.URI

object DepsParser {
  def parseTxtDeps(filename: URI): RIO[Blocking, List[String]] = DownloadableFileProvider.getFile(filename).flatMap(file => {
    file.openStream.use(inputStream => ZIO(scala.io.Source.fromInputStream(inputStream).getLines().filter(_.nonEmpty).map(_.trim).toList))
  })

  def flattenDeps(configDeps: List[String]): RIO[Blocking, List[String]] = {
    val txtUris = configDeps.filter(_.endsWith(".txt")).map(d => new URI(d))
    ZIO.foreach(txtUris)(parseTxtDeps).map(_.flatten).flatMap(txtDeps => {
      ZIO(configDeps.filter(!_.endsWith(".txt")) ++ txtDeps)
    })
  }
}