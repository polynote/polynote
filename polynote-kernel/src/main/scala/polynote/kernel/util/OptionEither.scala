package polynote.kernel.util

import scala.util.{Left => LeftEither, Right => RightEither}

/**
  * Represents a data type that is either an `A` or a `B` or NEITHER. I'm told it's kind of the "dual" of cats.data.Ior.
  *
  * 'tis right-biased.
  *
  * An instance of `A [[OptionEither]] B` is one of:
  *  - `[[OptionEither.Left Left]][A]`
  *  - `[[OptionEither.Right Right]][B]`
  *  - `[[OptionEither.Neither Neither]]`
  *
  * I basically copied the easiest 5% of the cats.data.Ior implementation and changed the Both to Neither. Hope it works.
  */
sealed abstract class OptionEither[+A, +B] extends Product with Serializable {

  final def fold[C](fa: A => C, fb: B => C, fn: => C): C = this match {
    case OptionEither.Left(a)  => fa(a)
    case OptionEither.Right(b) => fb(b)
    case OptionEither.Neither  => fn
  }

  final def isLeft: Boolean    = fold(_ => true,  _ => false, false)
  final def isRight: Boolean   = fold(_ => false, _ => true,  false)
  final def isNeither: Boolean = fold(_ => false, _ => false, true)

  final def left: Option[A]  = fold(a => Option(a), _ => None, None)
  final def right: Option[B] = fold(_ => None, b => Option(b), None)

  final def unwrap: Option[Either[A, B]] = fold(a => Option(LeftEither(a)), b => Option(RightEither(b)), None)

  final def bimap[C, D](fa: A => C, fb: B => D): C OptionEither D = fold(a => OptionEither.Left(fa(a)), b => OptionEither.Right(fb(b)), OptionEither.Neither)
  final def leftMap[C](f: A => C): C OptionEither B = bimap(f, identity)
  final def rightMap[D](f: B => D): A OptionEither D = bimap(identity, f)

  final def leftFlatMap[AA, BB >: B](f: A => AA OptionEither BB): OptionEither[AA, BB] = fold(a => f(a), b => OptionEither.Right(b), OptionEither.Neither)
  final def rightFlatMap[AA >: A, BB](f: B => AA OptionEither BB): OptionEither[AA, BB] = fold(a => OptionEither.Left(a), b => f(b), OptionEither.Neither)

  // all sorts of right-biased stuff
  final def map[D](f: B => D): A OptionEither D = rightMap(f)
  final def flatMap[AA >: A, BB](f: B => AA OptionEither BB): OptionEither[AA, BB] = rightFlatMap(f)

  final def exists(p: B => Boolean): Boolean = right.exists(p)
  final def forall(p: B => Boolean): Boolean = right.forall(p)
  final def getOrElse[BB >: B](bb: => BB): BB = right.getOrElse(bb)
  final def toOption: Option[B] = right
  final def toList: List[B] = right.toList
}

object OptionEither {
  final case class Left[+A, +B](a: A) extends (A OptionEither Nothing)
  final case class Right[+B](b: B) extends (Nothing OptionEither B)
  final case object Neither extends (Nothing OptionEither Nothing)

  def wrap[A, B](oe: Option[Either[A, B]]): A OptionEither B = oe match {
    case Some(LeftEither(a)) => OptionEither.Left(a)
    case Some(RightEither(b)) => OptionEither.Right(b)
    case None => OptionEither.Neither
  }
}
