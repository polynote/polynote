package polynote.kernel

import org.scalatest.{FreeSpec, Matchers}
import polynote.messages.{NotebookCell, CellID}

class ResultSpec extends FreeSpec with Matchers {

  "Result" - {

    "removes erased lines from the output" in {

      val outputs = List(
        "Hello, I'm a line.\n",
        "Hello, I'm a line that will be erased.",
        "\rHaha! I'm replacing that last line.\rDon't speak too soon, I replaced you too!",
        "\rI replaced all of you and here's a linefeed to make sure I stay around!\n"
      ).map {
        str => Output("text/plain; rel=stdout", str)
      }

      val results = Result.minimize(outputs)

      results should contain theSameElementsAs Seq(Output(
        "text/plain; rel=stdout",
        "Hello, I'm a line.\nI replaced all of you and here's a linefeed to make sure I stay around!\n"
      ))

    }

    "is reasonably fast in doing so" ignore {
      // just an informal benchmark to make sure this doesn't stall for minutes at a time
      var strings = List.fill(8192) {
        scala.util.Random.nextString(8192) + "\r"
      }

      var outputs = List.empty[Result]

      while (strings.nonEmpty) {
        val n = scala.util.Random.nextInt(strings.size) + 1
        outputs = Output("text/plain; rel=stdout", strings.take(n).mkString) :: outputs
        strings = strings.drop(n)
      }

      val start = System.nanoTime()
      val results = Result.minimize(outputs)
      val end = System.nanoTime()

      val millis = ((end - start).toDouble / 1e6).toInt
      println(s"$millis millis")
      // was consistently under 100ms for me
    }

  }

}
