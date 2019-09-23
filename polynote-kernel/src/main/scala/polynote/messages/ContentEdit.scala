package polynote.messages

import polynote.data.Rope
import scodec.Codec
import scodec.codecs.{Discriminated, Discriminator, byte}
import scodec.codecs.implicits._
import shapeless.cachedImplicit

import scala.collection.mutable.ListBuffer


sealed trait ContentEdit {
  def pos: Int

  def rebase(other: ContentEdits): ContentEdits = ContentEdits(ShortList(ContentEdit.rebaseAll(this, other.edits)._1))

  def applyTo(rope: Rope): Rope = this match {
    case Insert(pos, content) => rope.insertAt(pos, Rope(content))
    case Delete(pos, length)  => rope.delete(pos, length)
  }

  def nonEmpty: Boolean
}

final case class Insert(pos: Int, content: String) extends ContentEdit {
  require(pos >= 0, "pos >= 0")

  def nonEmpty: Boolean = content.nonEmpty

  override def toString: String = s"""Insert($pos, "$content")"""
}

object Insert {
  implicit val insertTag: Discriminator[ContentEdit, Insert, Byte] = Discriminator(0)
}

final case class Delete(pos: Int, length: Int) extends ContentEdit {
  require(pos >= 0, "pos >= 0")
  require(length >= 0, "length >= 0")
  
  override def nonEmpty: Boolean = length != 0
}

object Delete {
  implicit val deleteTag: Discriminator[ContentEdit, Delete, Byte] = Discriminator(1)
}

object ContentEdit {
  implicit val discriminated: Discriminated[ContentEdit, Byte] = Discriminated(byte)
  implicit val codec: Codec[ContentEdit] = cachedImplicit

  // rebase a onto b and b onto a
  def rebase(a: ContentEdit, b: ContentEdit): (List[ContentEdit], List[ContentEdit]) = (a, b) match {

    // if A is before B, or A and B are at the same spot but (A is shorter than B or equal in length but lexically before B)
    // then A comes before B.
    case (winner @ Insert(posA, contentA), Insert(posB, contentB)) if
    (posA < posB) ||
      (posA == posB &&
        (contentA.length < contentB.length || (contentA.length == contentB.length && contentA < contentB))) =>
      List(winner) -> List(Insert(posB + contentA.length, contentB))

    // Otherwise insert B comes before insert A
    case (Insert(posA, contentA), winner @ Insert(_, contentB)) =>
      List(Insert(posA + contentB.length, contentA)) -> List(winner)

    // If an insert comes before a delete, the delete moves forward by its length
    case (winner @ Insert(posA, contentA), Delete(posB, lengthB)) if posA <= posB =>
      List(winner) -> List(Delete(posB + contentA.length, lengthB))

    case (Delete(posA, lengthA), winner @ Insert(posB, contentB)) if posB <= posA =>
      List(Delete(posA + contentB.length, lengthA)) -> List(winner)

    // insert is in the middle of delete; delete has to split into before-insert and after-insert parts
    // and insert has to move back to where the delete started
    case (Insert(posA, contentA), Delete(posB, lengthB)) if posA < posB + lengthB =>
      val beforeLength = posA - posB
      List(Insert(posB, contentA)) -> List(Delete(posB, beforeLength), Delete(posB + contentA.length, lengthB - beforeLength))

    case (Delete(posA, lengthA), Insert(posB, contentB)) if posB < posA + lengthA =>
      val beforeLength = posB - posA
      List(Delete(posA, beforeLength), Delete(posA + contentB.length, lengthA - beforeLength)) -> List(Insert(posA, contentB))

    // insert is after delete - insert has to move back by deletion length
    case (Insert(posA, contentA), del @ Delete(_, lengthB)) =>
      List(Insert(posA - lengthB, contentA)) -> List(del)

    case (del @ Delete(_, lengthA), Insert(posB, contentB)) =>
      List(del) -> List(Insert(posB - lengthA, contentB))

    // delete A comes entirely before delete B;  move B back by A's length
    case (winner @ Delete(posA, lengthA), Delete(posB, lengthB)) if posA + lengthA <= posB =>
      List(winner) -> List(Delete(posB - lengthA, lengthB))

    // delete B comes entirely before delete A; move A back by B's length
    case (Delete(posA, lengthA), winner @ Delete(posB, lengthB)) if posB + lengthB <= posA =>
      List(Delete(posA - lengthB, lengthA)) -> List(winner)

    // B is entirely within A; A should shorten by B's length and B should disappear
    case (Delete(posA, lengthA), Delete(posB, lengthB)) if posB >= posA && posB + lengthB <= posA + lengthA =>
      List(Delete(posA, lengthA - lengthB)) -> Nil

    // A is entirely within B; opposite of above
    case (Delete(posA, lengthA), Delete(posB, lengthB)) if posA >= posB && posA + lengthA <= posB + lengthB =>
      Nil -> List(Delete(posB, lengthB - lengthA))

    // delete B starts in the middle of delete A; A should stop where B starts, and B should move to where A starts and shorten itself by the overlap
    case (Delete(posA, lengthA), Delete(posB, lengthB)) if posB > posA =>
      val overlap = posA + lengthA - posB
      List(Delete(posA, lengthA - overlap)) -> List(Delete(posA, lengthB - overlap))

    // delete A starts in the middle of B; opposite of above
    case (Delete(posA, lengthA), Delete(posB, lengthB)) if posA > posB =>
      val overlap = posB + lengthB - posA
      List(Delete(posB, lengthA - overlap)) -> List(Delete(posB, lengthB - overlap))
  }

  // rebase edit onto all of edits, and all of edits onto edit
  def rebaseAll(edit: ContentEdit, edits: Seq[ContentEdit]): (List[ContentEdit], List[ContentEdit]) = {
    val rebasedOther = ListBuffer[ContentEdit]()
    val rebasedEdit = edits.foldLeft(List(edit)) {
      (as, b) =>
        var bs = List(b)  // tracks the other edit rebased onto `as`, which is `edit` as it rebases through `edits`.
      val rebasedAs = as.flatMap {
        a =>
          bs match {
            case Nil => List(a)
            case one :: Nil =>
              val rebased = rebase(a, one)
              bs = rebased._2
              rebased._1
            case more =>
              val rebased = rebaseAll(a, more)
              bs = rebased._1
              rebased._1
          }
      }
        rebasedOther ++= bs
        rebasedAs
    }
    rebasedEdit -> rebasedOther.toList
  }
}

/**
  * Represents a sequence of [[ContentEdit]]s. Each edit in the sequence must be based upon (or independent of) the
  * edits before it.
  */
final case class ContentEdits(edits: ShortList[ContentEdit]) extends AnyVal {
  def applyTo(rope: Rope): Rope = rope.withEdits(this)

  /**
    * Given another set of edits which would act upon the same content as this set of edits, produce a new set of edits
    * which would have the same effect as this set of edits but are based upon the given edits.
    */
  def rebase(other: ContentEdits): ContentEdits = {
    // each edit in this set must be rebased to all the edits in the other set.
    // but we also have to track how the other edits are affected by each subsequent edit in this set, so that
    // the edits after it know how to rebase.
    val result = new ListBuffer[ContentEdit]
    val iter = edits.iterator.filter(_.nonEmpty)
    var otherEdits: List[ContentEdit] = other.edits

    while (iter.hasNext) {
      val edit = iter.next()
      val (rebasedEdit, rebasedOther) = ContentEdit.rebaseAll(edit, otherEdits)
      result ++= rebasedEdit
      otherEdits = rebasedOther
    }
    ContentEdits(ShortList(result.toList))
    //    ContentEdits(ShortList(edits.flatMap(_.rebase(other).edits)))
  }

  def rebase(other: ContentEdit): ContentEdits = rebase(ContentEdits(other))

  def size: Int = edits.size
}

object ContentEdits {
  def apply(edits: ContentEdit*): ContentEdits = ContentEdits(ShortList(edits.toList))
}