package polynote.env.ops

import polynote.env.macros.ZEnvMacros

trait Enrich[A, B] {
  def apply(a: A, b: B): A with B
}

object Enrich {

  implicit def enrichAny[B <: Any]: Enrich[Any, B] = new Enrich[Any, B] {
    def apply(a: Any, b: B): Any with B = b
  }

  implicit def materialize[A, B]: Enrich[A, B] = macro ZEnvMacros.mkEnrich[A, B]

}
