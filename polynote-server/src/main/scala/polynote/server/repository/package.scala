package polynote.server

import java.io.InputStream

import fs2.Chunk
import polynote.kernel.BaseEnv
import zio.{RIO, Task, ZIO}
import zio.blocking.effectBlocking
import zio.interop.catz._

import scala.concurrent.ExecutionContext

package object repository {

  def readBytes(is: => InputStream, chunkSize: Int, executionContext: ExecutionContext): RIO[BaseEnv, Chunk.Bytes] = {
    for {
      env    <- ZIO.environment[BaseEnv]
      ec     <- env.blocking.blockingExecutor.map(_.asEC)
      chunks <- fs2.io.readInputStream[Task](effectBlocking(is).provide(env), chunkSize, ec, closeAfterUse = true).compile.toChunk.map(_.toBytes)
    } yield chunks
  }

}
