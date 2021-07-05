package polynote.util

import org.scalatest.{FreeSpec, Matchers}

class VersionBufferSpec extends FreeSpec with Matchers {

  "VersionBuffer" - {
    "getRange" - {

      "normal conditions" in {
        val buf = new VersionBuffer[String]
        (0 until 50).foreach(i => buf.add(i, i.toString))

        buf.getRange(10, 20) shouldEqual (10 to 20).map(_.toString).toList
      }

      "wrap around" in {
        val buf = new VersionBuffer[String]
        ((Int.MaxValue - 40) to Int.MaxValue).foreach(i => buf.add(i, i.toString))
        (0 until 40).foreach(i => buf.add(i, i.toString))

        buf.getRange(Int.MaxValue - 20, 20) shouldEqual
          (((Int.MaxValue - 20) to Int.MaxValue).map(_.toString) ++ (0 to 20).map(_.toString)).toList
      }

    }
  }

}
