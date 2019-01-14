package polynote.server

import java.io.InputStream

import cats.effect.{ContextShift, IO}
import fs2.Chunk

import scala.concurrent.ExecutionContext

package object repository {

  def readBytes(is: => InputStream, chunkSize: Int, executionContext: ExecutionContext)(implicit shift: ContextShift[IO]): IO[Chunk.Bytes] =
    fs2.io.readInputStream(IO(is), chunkSize, executionContext, closeAfterUse = true).compile.toChunk.map(_.toBytes)

}
