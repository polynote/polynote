package polynote.kernel.util

import scala.util.{Left => LeftEither, Right => RightEither}

/**
  * Represents a data type that is either an `A` or a `B` or NEITHER. I'm told it's kind of the "dual" of cats.data.Ior.
  *
  * 'tis right-biased.
  *
  * An instance of `A [[Nand]] B` is one of:
  *  - `[[Nand.Left Left]][A]`
  *  - `[[Nand.Right Right]][B]`
  *  - `[[Nand.Neither Neither]]`
  *
  * I basically copied the easiest 5% of the cats.data.Ior implementation and changed the Both to Neither. Hope it works.
  */
sealed abstract class Nand[+A, +B] extends Product with Serializable {

  final def fold[C](fa: A => C, fb: B => C, fn: => C): C = this match {
    case Nand.Left(a)  => fa(a)
    case Nand.Right(b) => fb(b)
    case Nand.Neither  => fn
  }

  final def isLeft: Boolean    = fold(_ => true,  _ => false, false)
  final def isRight: Boolean   = fold(_ => false, _ => true,  false)
  final def isNeither: Boolean = fold(_ => false, _ => false, true)

  final def left: Option[A]  = fold(a => Option(a), _ => None, None)
  final def right: Option[B] = fold(_ => None, b => Option(b), None)

  final def unwrap: Option[Either[A, B]] = fold(a => Option(LeftEither(a)), b => Option(RightEither(b)), None)

  final def bimap[C, D](fa: A => C, fb: B => D): C Nand D = fold(a => Nand.Left(fa(a)), b => Nand.Right(fb(b)), Nand.Neither)
  final def leftMap[C](f: A => C): C Nand B = bimap(f, identity)
  final def rightMap[D](f: B => D): A Nand D = bimap(identity, f)

  final def leftFlatMap[AA, BB >: B](f: A => AA Nand BB): Nand[AA, BB] = fold(a => f(a), b => Nand.Right(b), Nand.Neither)
  final def rightFlatMap[AA >: A, BB](f: B => AA Nand BB): Nand[AA, BB] = fold(a => Nand.Left(a), b => f(b), Nand.Neither)

  // all sorts of right-biased stuff
  final def map[D](f: B => D): A Nand D = rightMap(f)
  final def flatMap[AA >: A, BB](f: B => AA Nand BB): Nand[AA, BB] = rightFlatMap(f)

  final def exists(p: B => Boolean): Boolean = right.exists(p)
  final def forall(p: B => Boolean): Boolean = right.forall(p)
  final def getOrElse[BB >: B](bb: => BB): BB = right.getOrElse(bb)
  final def toOption: Option[B] = right
  final def toList: List[B] = right.toList
}

object Nand {
  final case class Left[+A, +B](a: A) extends (A Nand Nothing)
  final case class Right[+B](b: B) extends (Nothing Nand B)
  final case object Neither extends (Nothing Nand Nothing)

  def wrap[A, B](oe: Option[Either[A, B]]): A Nand B = oe match {
    case Some(LeftEither(a)) => Nand.Left(a)
    case Some(RightEither(b)) => Nand.Right(b)
    case None => Nand.Neither
  }
}
