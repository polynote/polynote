package polynote.testing

import org.scalacheck.{Arbitrary, Gen}
import polynote.data.Rope
import polynote.messages.{CellID, ContentEdit, ContentEdits, Delete, DeleteCell, Insert, InsertCell, Notebook, NotebookCell, NotebookUpdate, ShortList, TinyString, UpdateCell}

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

  def genDeleteCell(notebook: Notebook, globalVersion: Int): Gen[DeleteCell] = notebook.cells.map(_.id) match {
    case Nil => Gen.fail
    case one :: Nil => Gen.const(DeleteCell(notebook.path, globalVersion, 0, one))
    case one :: two :: rest => Gen.oneOf(one, two, rest: _*).map {
      cellId => DeleteCell(notebook.path, globalVersion, 0, cellId)
    }
  }

  def genInsertCell(notebook: Notebook, globalVersion: Int): Gen[InsertCell] = notebook.cells.map(_.id) match {
    case Nil => genCell(CellID(0)).flatMap(cell => Gen.const(InsertCell(notebook.path, globalVersion, 0, cell, CellID(-1))))
    case ids@(first :: rest) =>
      val nextId = CellID(ids.max + 1)
      genCell(nextId).flatMap {
        cell => Gen.oneOf(
          Gen.oneOf(ids).map(after => InsertCell(notebook.path, globalVersion, 0, cell, after)),
          Gen.const(InsertCell(notebook.path, globalVersion, 0, cell, CellID(-1)))
        )
      }
  }

  def genUpdateCell(notebook: Notebook, globalVersion: Int): Gen[UpdateCell] = notebook.cells.toList match {
    case Nil   => Gen.fail
    case cells => Gen.oneOf(cells).flatMap {
      cell => genEditsFor(cell.content).map {
        case (edits, _) => UpdateCell(notebook.path, globalVersion, 0, cell.id, edits, None)
      }
    }
  }

  def genNotebookUpdate(notebook: Notebook, globalVersion: Int): Gen[NotebookUpdate] = Gen.oneOf(
    Gen.delay(if (notebook.cells.nonEmpty) genDeleteCell(notebook, globalVersion) else genInsertCell(notebook, globalVersion)),
    Gen.delay(genInsertCell(notebook, globalVersion)),
    Gen.delay(if (notebook.cells.nonEmpty) genUpdateCell(notebook, globalVersion) else genInsertCell(notebook, globalVersion))
  )

  def genNotebookUpdates(globalVersion: Int, initial: Notebook): Gen[(Notebook, List[NotebookUpdate])] = for {
    size  <- Gen.size
    (finalNotebook, edits) <- ((globalVersion + 1) to (globalVersion + size + 1)).foldLeft(Gen.const((initial, Queue.empty[NotebookUpdate]))) {
      (accum, nextVersion) => accum.flatMap {
        case (currentNotebook, updates) => genNotebookUpdate(currentNotebook, nextVersion).map {
          update => (update.applyTo(currentNotebook) -> updates.enqueue(update))
        }
      }
    }
  } yield finalNotebook -> edits.toList

}
