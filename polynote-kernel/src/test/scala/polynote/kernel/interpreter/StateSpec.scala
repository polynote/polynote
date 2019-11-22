package polynote.kernel.interpreter

import org.scalatest.{FreeSpec, Matchers}
import polynote.kernel.{ResultValueHelpers, ResultValue}

class StateSpec extends FreeSpec with Matchers with ResultValueHelpers {

  val one: State = State.id(1, values = List(1))
  val two: State = State.id(2, one, List(2))
  val three: State = State.id(3, two, List(3))
  val four: State = State.id(4, three, List(4))
  val five: State = State.id(5, four, List(5))

  "foreachPrev" - {
    "performs side-effects for each state in reverse order" in {
      val m = scala.collection.mutable.Buffer.empty[Any]

      five.foreachPrev {
        s =>
          s.values.foreach {
            v =>
              m += v.value
          }
      }

      m should contain theSameElementsAs Seq(5, 4, 3, 2, 1)
    }
  }

  "toList" - {
    "returns a list of states in reverse order" in {
      five.toList should contain theSameElementsAs Seq(five, four, three, two, one)
    }
  }

  "takeWhile" - {
    "returns everything when predicate is always true" in {
      five.takeWhile(_ => true) should contain theSameElementsAs Seq(five, four, three, two, one)
    }
    "returns nothing when the predicate is always false" in {
      five.takeWhile(_ => false) shouldBe empty
    }
    "takes until the predicate fails (exclusive) in reverse order" in {
      five.takeWhile(_.id > 3) should contain theSameElementsAs Seq(five, four)
    }
  }

  "takeUntil" - {
    "returns the current state when the predicate is always true" in {
      five.takeUntil(_ => true) should contain theSameElementsAs Seq(five)
    }

    "returns everything when the predicate is always false" in {
      five.takeUntil(_ => false) should contain theSameElementsAs Seq(five, four, three, two, one)
      //      five.takeUntil(_ => false) should contain theSameElementsAs Seq(five, four, three, two, one, State.Root) // <-- this was what it did without the changes I made in this commit
    }

    "takes until the predicate succeeds (inclusive) in reverse order" in {
      five.takeUntil(_.id <= 3) should contain theSameElementsAs Seq(five, four, three)
    }
  }

  "collect" - {
    "returns nothing if the pf is never defined" in {
      five.collect(PartialFunction.empty) shouldBe empty
    }

    "returns everything if the pf is always defined" in {
      five.collect { case x => x} should contain theSameElementsAs Seq(five, four, three, two, one)
    }

    "returns only those states where the pf is defined" in {
      five.collect {
        case s if s.id % 2 == 0 => s
      } should contain theSameElementsAs Seq(four, two)
    }
  }

  "scope" - {
    "returns a collapsed list of values" in {
      five.scope should contain theSameElementsAs Seq(five, four, three, two, one).flatMap(_.values)
    }
  }

  "rewindWhile" - {
    "returns the current state when the predicate is always false" in {
      five.rewindWhile(_ => false) shouldEqual five
    }

    "returns Root when the predicate is always true" in {
      five.rewindWhile(_ => true) shouldEqual State.Root
    }

    "returns the first state for which the predicate fails" in {
      five.rewindWhile(_.values.exists(_.valueAs[Int] > 2)) shouldEqual three
    }
  }

  "rewindUntil" - {
    "returns Root when the predicate is always false" in {
      five.rewindUntil(_ => false) shouldEqual State.Root
    }

    "returns the current state when the predicate is always true" in {
      five.rewindUntil(_ => true) shouldEqual five
    }

    "provides the first state for which the predicate holds" in {
      five.rewindUntil(_.values.exists(_.valueAs[Int] < 4)) shouldEqual three
    }
  }

  "lastPredef" - {
    "finds the last predef" in {
      val state = State.id(
        5, State.id(
          Short.MinValue + 1,
          State.id(Short.MinValue)))

      state.lastPredef shouldEqual State.id(Short.MinValue + 1, State.id(Short.MinValue))
    }
  }

  "insert" - {
    "inserts a state, ignoring its tail, in front of the state which shares its index" - {
      "at the head"  in {
        five.insert(5.toShort, two).toList should contain theSameElementsAs Seq(two.withPrev(five), five, four, three, two, one)
      }
      "in the middle" in {
        five.insert(3.toShort, two).toList should contain theSameElementsAs Seq(five.withPrev(four.withPrev(two.withPrev(three.withPrev(two.withPrev(one))))), four.withPrev(two.withPrev(three.withPrev(two.withPrev(one)))), two.withPrev(three.withPrev(two.withPrev(one))), three, two, one)
        two.insert(1.toShort, five).toList should contain theSameElementsAs Seq(two.withPrev(five.withPrev(one)), five.withPrev(one), one)
      }
      "at the end" in {
        two.insert(0.toShort, two).toList should contain theSameElementsAs Seq(two.withPrev(one.withPrev(two.withPrev(State.Root))), one.withPrev(two.withPrev(State.Root)), two.withPrev(State.Root))
      }
    }
    "inserts a state, ignoring its tail, at the end if its index is larger than all others" in {
      three.insert(100.toShort, one).toList should contain theSameElementsAs Seq(three.withPrev(two.withPrev(one.withPrev(one))), two.withPrev(one.withPrev(one)), one.withPrev(one), one)
    }

    "ignores the tail of states inserted at root" in {
      State.root.insert(1.toShort, five).toList should contain theSameElementsAs Seq(five.withPrev(State.Root))
    }
  }

  "insertOrReplace" - {
    "replaces, including its tail, the state with the same id" - {
      "at the head" in {
        val newFive = State.id(5, two, List(500))
        five.insertOrReplace(newFive).toList should contain theSameElementsAs Seq(newFive.withPrev(two), two, one)
        newFive.insertOrReplace(five).toList should contain theSameElementsAs Seq(five, four, three, two, one)
      }

      "in the middle" in {
        val newThree = State.id(3, one, List(200))
        five.insertOrReplace(newThree).toList should contain theSameElementsAs Seq(five.withPrev(four.withPrev(newThree)), four.withPrev(newThree), newThree, one)
        newThree.insertOrReplace(five).toList should contain theSameElementsAs Seq(newThree.withPrev(one.withPrev(five.withPrev(State.Root))), one.withPrev(five.withPrev(State.Root)), five.withPrev(State.Root))
      }

      "at the end" in {
        val newOne = State.id(1, State.Root, List(100))
        three.insertOrReplace(newOne).toList should contain theSameElementsAs Seq(three.withPrev(two.withPrev(newOne)), two.withPrev(newOne), newOne)
        newOne.insertOrReplace(three).toList should contain theSameElementsAs Seq(newOne.withPrev(three.withPrev(State.Root)), three.withPrev(State.Root))
      }
    }

    "inserts a state, ignoring its tail, at the end if its index is larger than all others and its tail doesn't match" in {
      val newState = State.id(100, five, List(600))
      three.insertOrReplace(newState).toList should contain theSameElementsAs Seq(three.withPrev(two.withPrev(one.withPrev(newState.withPrev(State.Root)))), two.withPrev(one.withPrev(newState.withPrev(State.Root))), one.withPrev(newState.withPrev(State.Root)), newState.withPrev(State.Root))
    }

    "inserts a state at the head if its tail matches the current head" in {
      val six = State.id(6, five, List(600))
      five.insertOrReplace(six).toList should contain theSameElementsAs Seq(six, five, four, three, two, one)

      val seven = State.id(7, six, List(700))
      five.insertOrReplace(six).insertOrReplace(seven).toList should contain theSameElementsAs Seq(seven, six, five, four, three, two, one)
    }

    "can be used to fill 'gaps' in the state" in {
      val gapState = five.withPrev(three)
      gapState.toList should contain theSameElementsAs Seq(five.withPrev(three), three, two, one)
      gapState.insertOrReplace(four).toList should contain theSameElementsAs Seq(five, four, three, two, one)
    }

    "ignores the tail of states inserted at root" in {
      State.root.insert(1.toShort, five).toList should contain theSameElementsAs Seq(five.withPrev(State.Root))
    }

  }

}
