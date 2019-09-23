package polynote.testing

import org.scalacheck.{Arbitrary, Gen}
import polynote.data.Rope
import polynote.messages.{CellID, ContentEdit, ContentEdits, Delete, Insert, NotebookCell, ShortList, TinyString}

import scala.collection.immutable.Queue

object Generators {


  val genRope: Gen[Rope] = Gen.asciiPrintableStr.map(Rope.apply)  // TODO: use arbitrary[String]

  def genDelete(docLen: Int): Gen[Delete] = for {
    pos <- Gen.choose(0, docLen - 1)
    len <- Gen.choose(1, docLen - pos)
  } yield Delete(pos, len)

  def genInsert(docLen: Int): Gen[Insert] = for {
    pos <- Gen.choose(0, docLen - 1)
    str <- Gen.asciiPrintableStr
  } yield Insert(pos, str)

  def genEdit(len: Int): Gen[ContentEdit] = if (len > 0) {
    Gen.oneOf(genDelete(len), genInsert(len))
  } else Gen.asciiPrintableStr.map(Insert(0, _))

  def genEditsFor(init: Rope) = for {
    size  <- Gen.size
    count <- Gen.choose(0, size)
    (finalRope, edits) <- (0 until count).foldLeft(Gen.const((init, Queue.empty[ContentEdit]))) {
      (g, _) => g.flatMap {
        case (rope, edits) => genEdit(rope.size).map {
          edit => (rope.withEdit(edit), edits enqueue edit)
        }
      }
    }
  } yield (ContentEdits(ShortList(edits.toList)), finalRope)

  // generate an initial string, and a bunch of edits to apply to it.
  val genEdits = for {
    init               <- genRope
    (edits, finalRope) <- genEditsFor(init)
  } yield (init, edits, finalRope)

  // to really cover ground on testing the rope, we can arbitrarily fragment the rope's structure by
  // applying a bunch of edits to it
  implicit val arbRope: Arbitrary[Rope] = Arbitrary(genEdits.map(_._3))

  def genCell(id: CellID): Gen[NotebookCell] = for {
    lang <- Gen.oneOf[TinyString]("text", "scala", "python")
    content <- genRope
    // TODO: also generate results & metadata
  } yield NotebookCell(id, lang, content)

}
