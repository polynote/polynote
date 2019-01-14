package polynote.kernel.util

import cats.effect.IO
import fs2.concurrent.{Enqueue, Queue}
import polynote.kernel.Result

class ResultOutputStream(
  results: Enqueue[IO, Result]
) {

}
