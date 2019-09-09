package polynote.kernel.interpreter

import org.scalatest.{FreeSpec, Matchers}
import polynote.kernel.ResultValue

class StateSpec extends FreeSpec with Matchers {

  "insertOrReplace" - {

    "replaces the state with the same ID" in {
      val two = State.id(
        2,
        State.id(1))

      val state = State.id(
        5,
        State.id(
          4,
          State.id(
            3,
            two)))

      val newThreeValues = List(new ResultValue("hi", "foo", Nil, 3.toShort, (), null, None))
      val newThree = State.id(3, two, newThreeValues)

      val result = state.insertOrReplace(newThree)

      result.prev.prev.values shouldEqual newThreeValues
    }

    "inserts the state if it doesn't exist" in {
      val two = State.id(
        2,
        State.id(1))

      val state = State.id(
        5,
        State.id(
          4,
          two))

      val three = State.id(3, two)

      val result = state.insertOrReplace(three)
      result shouldEqual State.id(5, State.id(4, three))
    }

  }

}
