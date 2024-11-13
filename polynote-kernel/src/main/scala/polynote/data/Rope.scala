
/* This code was taken from Tekstlib - https://github.com/gnieh/tekstlib

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package polynote.data

import io.circe.{Decoder, Encoder}
import polynote.messages.{ContentEdit, ContentEdits, ShortString}
import scodec.Codec

import scala.collection.immutable.Queue
import scala.collection.mutable.{ArrayBuffer, ListBuffer}

/** Rope data structure as a binary tree of character arrays.
  *  First described in `Ropes: an Alternative to Strings`
  *  by Hans-J. Boehm, Russ Atkinson and Michael Plass.
  *
  *  @author Lucas Satabin
  */
sealed abstract class Rope {

  def size: Int

  def depth: Int

  def charAt(idx: Int): Char

  def splitAt(idx: Int): (Rope, Rope)

  def withEdit(edit: ContentEdit): Rope = edit.applyTo(this)

  def withEdits(edits: ContentEdits): Rope = edits.edits.foldLeft(this)(_ withEdit _)

  def insertAt(idx: Int, that: Rope): Rope = {
    val (r1, r2) = splitAt(idx)
    r1 + that + r2
  }

  def delete(start: Int, length: Int): Rope = {
    val (r1, r2) = splitAt(start)
    val (_, r3) = r2.splitAt(length)
    r1 + r3
  }

  def +(that: Rope): Rope

  def +(s: String): Rope =
    this + RopeLeaf(s.toArray)

  def +(a: Array[Char]): Rope =
    this + RopeLeaf(a)

  def +(c: Char): Rope =
    this + RopeLeaf(Array(c))

  // should this match String's substring(beginIndex, endIndex) API?
  def substring(start: Int, length: Int): Rope = {
    val (_, r1) = splitAt(start)
    val (r2, _) = r1.splitAt(length)
    r2
  }

  def substring(start: Int): Rope = {
    val (_, r1) = splitAt(start)
    r1
  }

  def toString: String

  override def equals(obj: Any): Boolean = obj match {
    case r: Rope => this.size == r.size && this.toString == r.toString
    case s: String => s == this.toString
    case somethingElse => super.equals(somethingElse)
  }

  def map(f: Char => Char): Rope

  def flatMap(f: Char => Rope): Rope

  def filter(f: Char => Boolean): Rope

  def withFilter(f: Char => Boolean): Rope =
    filter(f)

  def foreach(f: Char => Unit): Unit

  def foldLeft[Acc](zero: Acc)(f: (Acc, Char) => Acc): Acc

  def foldRight[Acc](zero: Acc)(f: (Char, Acc) => Acc): Acc

  private[data] def isBalanced: Boolean

  private[data] def toList: List[Array[Char]]

}

object Rope {

  /** Creates a rope from a string */
  def apply(s: String): Rope =
    Rope(s.toArray)

  /** Creates a rope from a character array. */
  def apply(a: Array[Char]): Rope =
    if (a == null || a.length == 0) {
      RopeEmpty
    } else if (a.length > thresh) {
      val (a1, a2) = a.splitAt(a.length / 2)
      Rope(a1) + Rope(a2)
    } else {
      RopeLeaf(a)
    }

  /** Creates a rope from a character. */
  def apply(c: Char): Rope =
    RopeLeaf(Array(c))

  private[data] def balance(r: Rope): Rope =
    if (r.isBalanced)
      r
    else
      fromList(r.toList)

  private def fromList(l: List[Array[Char]]): Rope =
    l match {
      case List(s1, s2) =>
        RopeConcat(RopeLeaf(s1), RopeLeaf(s2))
      case List(s) =>
        RopeLeaf(s)
      case Nil =>
        RopeEmpty
      case _ =>
        val (half1, half2) = l.splitAt(l.size / 2)
        RopeConcat(fromList(half1), fromList(half2))
    }

  implicit val encoder: Encoder[Rope] = Encoder.encodeString.contramap(_.toString)
  implicit val decoder: Decoder[Rope] = Decoder.decodeString.map(Rope.apply)
  implicit val codec: Codec[Rope] = scodec.codecs.implicits.implicitStringCodec.xmap(Rope.apply, _.toString)
  
  implicit def fromString(str: String): Rope = Rope(str)

  val thresh = 2048

}

private case object RopeEmpty extends Rope {

  def size: Int =
    0

  def depth: Int =
    0

  def charAt(idx: Int): Char =
    throw new StringIndexOutOfBoundsException(f"String index out of range: $idx")

  def splitAt(idx: Int): (Rope, Rope) =
    (RopeEmpty, RopeEmpty)

  def +(that: Rope): Rope =
    that

  override def toString: String =
    ""
  def map(f: Char => Char): Rope =
    this

  def flatMap(f: Char => Rope): Rope =
    this

  def filter(f: Char => Boolean): Rope =
    this

  def foreach(f: Char => Unit): Unit =
    ()

  def foldLeft[Acc](zero: Acc)(f: (Acc, Char) => Acc): Acc =
    zero

  def foldRight[Acc](zero: Acc)(f: (Char, Acc) => Acc): Acc =
    zero

  private[data] val isBalanced: Boolean =
    true

  private[data] def toList: List[Array[Char]] =
    Nil

}

private final case class RopeConcat(left: Rope, right: Rope) extends Rope {

  val size: Int =
    left.size + right.size

  val depth =
    1 + math.max(left.depth, right.depth)

  def charAt(idx: Int): Char =
    if (idx < 0 || idx >= size)
      throw new StringIndexOutOfBoundsException(f"String index out of range: $idx")
    else if (idx < left.size)
      left.charAt(idx)
    else
      right.charAt(idx - left.size)

  def splitAt(idx: Int): (Rope, Rope) =
    if (idx < 0) {
      (RopeEmpty, this)
    } else if (idx >= size) {
      (this, RopeEmpty)
    } else if (idx >= left.size) {
      val (r1, r2) = right.splitAt(idx - left.size)
      (left + r1, r2)
    } else {
      val (r1, r2) = left.splitAt(idx)
      (r1, r2 + right)
    }

  def +(that: Rope): Rope =
    (right, that) match {
      case (_, RopeEmpty) =>
        this
      case (RopeLeaf(rightValue), RopeLeaf(thatValue)) if rightValue.length + thatValue.length <= Rope.thresh =>
        Rope.balance(left + Rope(rightValue ++ thatValue))
      case _ =>
        Rope.balance(RopeConcat(this, that))
    }

  override def toString =
    left.toString + right.toString

  def map(f: Char => Char): Rope =
    left.map(f) + right.map(f)

  def flatMap(f: Char => Rope): Rope =
    left.flatMap(f) + right.flatMap(f)

  def filter(f: Char => Boolean): Rope =
    left.filter(f) + right.filter(f)

  def foreach(f: Char => Unit): Unit = {
    left.foreach(f)
    right.foreach(f)
  }

  def foldLeft[Acc](zero: Acc)(f: (Acc, Char) => Acc): Acc =
    right.foldLeft(left.foldLeft(zero)(f))(f)

  def foldRight[Acc](zero: Acc)(f: (Char, Acc) => Acc): Acc =
    left.foldRight(right.foldRight(zero)(f))(f)

  private[data] val isBalanced: Boolean =
    math.abs(left.depth - right.depth) < 4

  private[data] def toList: List[Array[Char]] =
    left.toList ++ right.toList

}

private final case class RopeLeaf(value: Array[Char]) extends Rope {

  val size =
    value.length

  val depth =
    0

  def charAt(idx: Int): Char =
    value(idx)

  def splitAt(idx: Int): (Rope, Rope) = {
    val (s1, s2) = value.splitAt(idx)
    (Rope(s1), Rope(s2))
  }

  def +(that: Rope): Rope =
    that match {
      case RopeEmpty =>
        this
      case RopeLeaf(thatValue) if this.value.length + thatValue.length <= Rope.thresh =>
        Rope(this.value ++ thatValue)
      case _ =>
        Rope.balance(RopeConcat(this, that))
    }

  override def toString =
    value.mkString

  def map(f: Char => Char): Rope =
    RopeLeaf(value.map(f))

  def flatMap(f: Char => Rope): Rope =
    value.foldLeft(Rope("")) { (acc, c) => acc + f(c) }

  def filter(f: Char => Boolean): Rope =
    Rope(value.filter(f))

  def foreach(f: Char => Unit): Unit =
    value.foreach(f)

  def foldLeft[Acc](zero: Acc)(f: (Acc, Char) => Acc): Acc =
    value.foldLeft(zero)(f)

  def foldRight[Acc](zero: Acc)(f: (Char, Acc) => Acc): Acc =
    value.foldRight(zero)(f)

  private[data] val isBalanced: Boolean =
    true

  private[data] def toList: List[Array[Char]] =
    List(value)

}
