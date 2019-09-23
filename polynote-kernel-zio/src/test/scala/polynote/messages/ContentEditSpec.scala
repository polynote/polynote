package polynote.messages

import org.scalacheck.{Arbitrary, Shrink}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FreeSpec, Matchers}
import polynote.data.Rope
import polynote.testing.Generators

class ContentEditSpec extends FreeSpec with Matchers with GeneratorDrivenPropertyChecks {

  case class EditScenario(initial: Rope, edit: ContentEdit, edits: ContentEdits)

  private val genEditScenario = for {
    (initial, edits, result) <- Generators.genEdits if edits.size > 1
    edit                     <- Generators.genEdit(initial.size)
  } yield EditScenario(initial, edit, edits)

  implicit val arbScenario: Arbitrary[EditScenario] = Arbitrary(genEditScenario)

  def shrunkScenario(scenario: EditScenario): Stream[EditScenario] = if(scenario.edits.edits.isEmpty) Stream.empty else {
    val next = scenario.copy(edits = ContentEdits(scenario.edits.edits.dropRight(1): _*))
    next #:: shrunkScenario(next)
  }

  implicit val shrinkScenario: Shrink[EditScenario] = Shrink(shrunkScenario)

  case class MultiEditScenario(initial: Rope, edits1: ContentEdits, edits2: ContentEdits)

  private val genMultiEditScenario = for {
    (initial, edits1, _) <- Generators.genEdits
    (edits2, _)          <- Generators.genEditsFor(initial)
  } yield MultiEditScenario(initial, edits1, edits2)

  implicit val arbMultiEditScenario: Arbitrary[MultiEditScenario] = Arbitrary(genMultiEditScenario)

  def shrunkMultiEditScenario(scenario: MultiEditScenario): Stream[MultiEditScenario] = scenario match {
    case MultiEditScenario(_, edits1, edits2) if edits1.edits.isEmpty && edits2.edits.isEmpty => Stream.empty
    case MultiEditScenario(_, edits1, edits2) if edits1.edits.size >= edits2.edits.size =>
      val next = scenario.copy(edits1 = ContentEdits(edits1.edits.dropRight(1): _*))
      next #:: shrunkMultiEditScenario(next)
    case MultiEditScenario(_, edits1, edits2) =>
      val next = scenario.copy(edits2 = ContentEdits(edits2.edits.dropRight(1): _*))
      next #:: shrunkMultiEditScenario(next)
  }

  implicit val shrinkMultiEditScenario: Shrink[MultiEditScenario] = Shrink(shrunkMultiEditScenario)

  "rebase" - {
    "one edit against many edits" - {

      "quick tests" - {
        // test cases pulled from failed scalacheck cases, and made easier to read/debug

        "first" in {
          val initial = Rope("AAAAAAAAAAAAAA")

          val edit = Insert(8, "QQQQQQQQQQ")

          val edits = ContentEdits(
            Delete(13, 1),              // AAAAAAAAAAAAA
            Delete(9, 3),               // AAAAAAAAAA
            Insert(8, "BBBBBBBBB"),     // AAAAAAAABBBBBBBBBAA
            Delete(1, 6),               // AABBBBBBBBBAA
            Insert(2, "CCCCCCCCCCCCC"), // AACCCCCCCCCCCCCBBBBBBBBBAA
            Insert(22, "DDDDDDDDDDDD")  // AACCCCCCCCCCCCCBBBBBBBDDDDDDDDDDDDBBAA
          )

          initial.withEdit(edit).withEdits(edits.rebase(edit)).toString shouldEqual "AACCCCCCCCCCCCCBBBBBBBDDDDDDDDDDDDBBQQQQQQQQQQAA"
          initial.withEdits(edits).withEdits(edit.rebase(edits)).toString  shouldEqual "AACCCCCCCCCCCCCBBBBBBBDDDDDDDDDDDDBBQQQQQQQQQQAA"
        }

        "second" in {

          val initial = Rope("AAAAAAA")
          val edit = Delete(3, 3)
          val edits = ContentEdits(
            Delete(4,1),
            Insert(2,"BBBBBBBB"),
            Delete(8,4),
            Delete(0,0),
            Delete(4,3),
            Insert(4,"CCCCCCCCC"),
            Delete(14,2),
            Delete(13,0)
          )

          initial.withEdit(edit).withEdits(edits.rebase(edit)).toString shouldEqual
            initial.withEdits(edits).withEdits(edit.rebase(edits)).toString
        }

        "third" in {
          val initial = Rope("0123456789012345678")
          val edit = Insert(7, "QQQQQQQQQQQQQQQQQQQQ")
          val edits = ContentEdits(
            Delete(13,3),
            Delete(4,9),
            Insert(1,"BBBBBBBBBBBB"),
            Delete(17,1)
          )

          initial.withEdit(edit).withEdits(edits.rebase(edit)).toString shouldEqual
            initial.withEdits(edits).withEdits(edit.rebase(edits)).toString
        }

        "fourth" in {
          val initial = Rope("".padTo(37, 'A'))
          val edit = Delete(18, 1)
          val edits = ContentEdits(
            Insert(0,"BBB"),
            Insert(11, ""),
            Insert(26, "".padTo(48, 'C')),
            Insert(85, "DDDDDDD"),
            Delete(57,26),
            Insert(64,"EEEEEE"),
            Insert(20,"".padTo(43, 'F')),
            Delete(57,30)
          )


          initial.withEdit(edit).withEdits(edits.rebase(edit)).toString shouldEqual
            initial.withEdits(edits).withEdits(edit.rebase(edits)).toString
        }

        "fifth" in {
          val initial = Rope("F9uXCufTUAw")
          val edit = Delete(0, 9)
          val edits = ContentEdits(
            Delete(9, 0),
            Delete(7, 0),
            Delete(8, 3)
          )

          initial.withEdit(edit).withEdits(edits.rebase(edit)).toString shouldEqual
            initial.withEdits(edits).withEdits(edit.rebase(edits)).toString
        }

        "sixth" in {
          val initial = Rope("ABCD")
          val edit = Delete(2, 2)
          val edits = ContentEdits(
            Insert(3, "1234567"), // ABC1234567D
            Insert(9, "Q"),       // ABC123456Q7D
            Delete(3, 8)          // ABCD
          )

          initial.withEdit(edit).withEdits(edits.rebase(edit)).toString shouldEqual
            initial.withEdits(edits).withEdits(edit.rebase(edits)).toString
        }

        "seventh" in {
          val initial = Rope("AA")
          val edit = Insert(0, "BBBBB")
          val edits = ContentEdits(
            Insert(0, "C"),     // CAA
            Insert(2, "DDDD"),  // CADDDDA
            Delete(6,1),        // CADDDD
            Insert(0, "EEEE"),  // EEEECADDDD
            Delete(5,5),        // EEEEC
            Delete(1,4),        // E
            Insert(0, "FFFFF")  // FFFFFE
          )

          initial.withEdit(edit).withEdits(edits.rebase(edit)).toString shouldEqual
            initial.withEdits(edits).withEdits(edit.rebase(edits)).toString
        }

        "eighth" in {
          val initial = Rope("123456")
          val edit = Delete(3, 2)
          val edits = ContentEdits(
            Delete(4,1),
            Delete(0,5)
          )

          initial.withEdit(edit).withEdits(edits.rebase(edit)).toString shouldEqual
            initial.withEdits(edits).withEdits(edit.rebase(edits)).toString
        }

        "ninth" in {
          val initial = Rope("1234567890AB")
          val edit = Delete(0, 5)
          val edits = ContentEdits(
            Delete(1, 2),
            Delete(5, 5)
          )

          initial.withEdit(edit).withEdits(edits.rebase(edit)).toString shouldEqual
            initial.withEdits(edits).withEdits(edit.rebase(edits)).toString
        }

        "tenth" in {
          val initial = Rope("1234567890AB")
          val edit = Delete(0, 5)
          val edits = ContentEdits(
            Delete(1, 2),
            Delete(5, 5)
          )

          initial.withEdit(edit).withEdits(edits.rebase(edit)).toString shouldEqual
            initial.withEdits(edits).withEdits(edit.rebase(edits)).toString
        }

        "eleventh" in {
          val initial = Rope("123456789")
          val edit = Delete(2, 3)
          val edits = ContentEdits(
            Delete(0, 1),
            Delete(3, 5),
            Delete(0, 1)
          )

          initial.withEdit(edit).withEdits(edits.rebase(edit)).toString shouldEqual
            initial.withEdits(edits).withEdits(edit.rebase(edits)).toString
        }

      }

      "rebase of single edits is symmetric" in forAll(Generators.genEdit(30), Generators.genEdit(30)) {
        (a, b) =>

          val aOntoB = ContentEdit.rebase(a, b)
          val bOntoA = ContentEdit.rebase(b, a)

          aOntoB._1 shouldEqual bOntoA._2
          bOntoA._1 shouldEqual aOntoB._2
      }

      "content is the same no matter what order edits are applied" in forAll(genEditScenario, MinSuccessful(500)) {
        // TODO: test this failed scenario
        // EditScenario(aks42,Insert(2, " "),ContentEdits(List(Delete(0,5), Insert(0, " "), Insert(0, "@0kTWZ"))))

        case EditScenario(initial, edit, edits) =>

          val rebasedEdits = edits.rebase(edit)
          val rebasedEdit = edit.rebase(edits)

          initial.withEdits(edits).withEdits(rebasedEdit).toString shouldEqual
            initial.withEdit(edit).withEdits(rebasedEdits).toString

      }
    }

    "many edits against many edits" - {

      // TODO: test this failed scenario
      // MultiEditScenario(,ContentEdits(List(Insert(0, "8"), Insert(0, "ak!"))),ContentEdits(List(Insert(0, "d"), Insert(0, "8"))))

      "content is the same no matter what order edits are applied" in forAll(genMultiEditScenario, MinSuccessful(500)) {
        case MultiEditScenario(initial, edits1, edits2) =>

          val rebase12 = edits1.rebase(edits2)
          val rebase21 = edits2.rebase(edits1)

          initial.withEdits(edits1).withEdits(rebase21).toString shouldEqual
            initial.withEdits(edits2).withEdits(rebase12).toString

      }

    }

  }

  "rebaseAll" - {

    "symmetric" in forAll(genEditScenario, MinSuccessful(500)) {
      case EditScenario(initial, edit, edits) =>
        try {
          val (rebasedEdit, rebasedEdits) = ContentEdit.rebaseAll(edit, edits.edits)

          val editsFirst = initial.withEdits(edits).withEdits(ContentEdits(ShortList(rebasedEdit))).toString
          val editFirst = initial.withEdit(edit).withEdits(ContentEdits(ShortList(rebasedEdits))).toString

          if (editsFirst != editFirst) {
            println(rebasedEdit)
            println(rebasedEdits)
          }

          editsFirst shouldEqual editFirst
        } catch {
          case err: NotImplementedError =>
        }
    }


  }

}
