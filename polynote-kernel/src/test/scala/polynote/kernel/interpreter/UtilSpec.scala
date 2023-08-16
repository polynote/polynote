package polynote.kernel.interpreter

import org.scalacheck.{Gen, Shrink}
import org.scalatest.{FreeSpec, Matchers}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks


class UtilSpec extends FreeSpec with ScalaCheckDrivenPropertyChecks with Matchers {

  "posToLineAndColumn" - {
    "some test cases" in {
      val str =
        """This is a string.
          |It is a multiline string.
          |
          |Some of the lines are empty.
          |
          |Let's pick some positions in it and check the function.""".stripMargin

      posToLineAndColumn(str, 0) shouldEqual (1, 0)
      posToLineAndColumn(str, 1) shouldEqual (1, 1)
      posToLineAndColumn(str, 17) shouldEqual (1, 17)
      posToLineAndColumn(str, 18) shouldEqual (2, 0)
      posToLineAndColumn(str, 43) shouldEqual (2, 25)
      posToLineAndColumn(str, 44) shouldEqual (3, 0)
      posToLineAndColumn(str, 45) shouldEqual (4, 0)
    }


    // invariant: substring from a given position from the whole string should be the same as throwing away the lines before
    //              and then substring from the computed column and then merging the remaining lines.
    val genLines = Gen.nonEmptyListOf(Gen.alphaNumStr).suchThat(_.mkString.nonEmpty)
    val genLinesAndStrAndPos = for {
      lines <- genLines
      str    = lines.mkString("\n")
      pos   <- Gen.choose(0, str.length - 1)
    } yield (lines, str, pos)

    implicit val noShrink: Shrink[(List[String], String, Int)] = Shrink {
      _ => Stream.empty
    }

    "check invariant" in {
      forAll(genLinesAndStrAndPos) {
        case (lines, str, pos) =>
          if (pos == -1)
            throw new Exception("WTF")
          val (line, column) = posToLineAndColumn(str, pos)
          val zeroBasedLine = line - 1
          val splitAtLineAndColumn = lines.drop(zeroBasedLine) match {
            case Nil           => ""
            case first :: rest => (first.substring(column) :: rest).mkString("\n")
          }
          val splitAtPos = str.substring(pos)
          splitAtLineAndColumn shouldEqual splitAtPos
      }
    }
  }

}
