package polynote.kernel

import scala.reflect.runtime.universe.typeOf

// Some helpers for dealing with ResultValues
trait ResultValueHelpers {

  implicit def int2ResultValue(i: Int): ResultValue = {
    new ResultValue(i.toString, "", Nil, 0.toShort, i, typeOf[Int], None)
  }

  implicit class ResultValueOps(rv: ResultValue) {
    def valueAs[T]: T = {
      rv.value.asInstanceOf[T]
    }
  }
}
