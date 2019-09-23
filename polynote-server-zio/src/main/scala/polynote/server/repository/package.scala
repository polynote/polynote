package polynote.server

import java.io.InputStream

import cats.effect.{ContextShift, IO, Sync}
import cats.syntax.functor._
import fs2.Chunk

import scala.concurrent.ExecutionContext

package object repository {

  def readBytes[F[_]](is: => InputStream, chunkSize: Int, executionContext: ExecutionContext)(implicit F: Sync[F], shift: ContextShift[F]): F[Chunk.Bytes] =
    fs2.io.readInputStream(F.delay(is), chunkSize, executionContext, closeAfterUse = true).compile.toChunk.map(_.toBytes)

}
