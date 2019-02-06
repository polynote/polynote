package polynote.test

import org.scalacheck.{Arbitrary, Gen}
import Arbitrary.arbitrary
import polynote.data.Rope
import polynote.messages.{ContentEdit, NotebookCell, TinyString}

import scala.collection.immutable.Queue

object Generators {


  val genRope: Gen[Rope] = Gen.asciiPrintableStr.map(Rope.apply)  // TODO: use arbitrary[String]

  def genEdit(len: Int): Gen[ContentEdit] = if (len > 0) {
    for {
      pos <- Gen.choose(0, len - 1)
      len <- Gen.choose(0, len - pos)
      content <- Gen.asciiPrintableStr
    } yield ContentEdit(pos, len, content)
  } else for {
    content <- Gen.asciiPrintableStr
  } yield ContentEdit(0, 0, content)

  def genEdits(init: Rope) = for {
    size  <- Gen.size
    count <- Gen.choose(0, size)
    (finalRope, edits) <- (0 until count).foldLeft(Gen.const((init, Queue.empty[ContentEdit]))) {
      (g, _) => g.flatMap {
        case (rope, edits) => genEdit(rope.size).map {
          edit => (rope.withEdit(edit), edits enqueue edit)
        }
      }
    }
  } yield (edits.toList, finalRope)

  // generate an initial string, and a bunch of edits to apply to it.
  val genEdits = for {
    init               <- genRope
    (edits, finalRope) <- genEdits(init)
  } yield (init, edits, finalRope)

  // to really cover ground on testing the rope, we can arbitrarily fragment the rope's structure by
  // applying a bunch of edits to it
  implicit val arbRope: Arbitrary[Rope] = Arbitrary(genEdits.map(_._3))

  def genCell(id: String): Gen[NotebookCell] = for {
    lang <- Gen.oneOf[TinyString]("text", "scala", "python")
    content <- genRope
    // TODO: also generate results & metadata
  } yield NotebookCell(id, lang, content)

}
