package polynote.messages

import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FreeSpec, Matchers}
import polynote.data.Rope
import polynote.test.Generators

import scala.collection.immutable.Queue

class ContentEditSpec extends FreeSpec with Matchers with GeneratorDrivenPropertyChecks {

  case class EditScenario(initial: Rope, edit: ContentEdit, edits: List[ContentEdit])

  private val genEditScenario = for {
    (initial, edits, result) <- Generators.genEdits if edits.size > 1
    edit                     <- Generators.genEdit(initial.size)
  } yield EditScenario(initial, edit, edits)

  "rebase" - {

    "quick tests" - {

      "completely before" - {
        "replace with same length" in {
          val rope = Rope("abcdefg")
          val edit1 = ContentEdit(1, 2, "XY")
          val edit2 = ContentEdit(4, 2, "Z")

          rope.withEdit(edit1).withEdit(edit2.rebase(edit1)).toString shouldEqual "aXYdZg"
          rope.withEdit(edit2).withEdit(edit1.rebase(edit2)).toString shouldEqual "aXYdZg"
        }

        "insert only" in {
          val rope = Rope("abcdefg")
          val edit1 = ContentEdit(1, 0, "XY")
          val edit2 = ContentEdit(4, 2, "Z")

          rope.withEdit(edit1).withEdit(edit2.rebase(edit1)).toString shouldEqual "aXYbcdZg"
          rope.withEdit(edit2).withEdit(edit1.rebase(edit2)).toString shouldEqual "aXYbcdZg"
        }

        "delete only" in {
          val rope = Rope("abcdefg")
          val edit1 = ContentEdit(1, 2, "")
          val edit2 = ContentEdit(4, 2, "Z")

          rope.withEdit(edit1).withEdit(edit2.rebase(edit1)).toString shouldEqual "adZg"
          rope.withEdit(edit2).withEdit(edit1.rebase(edit2)).toString shouldEqual "adZg"
        }
      }

      "completely after" - {
        "replace with same length" in {
          val rope = Rope("abcdefg")
          val edit1 = ContentEdit(4, 2, "XY")
          val edit2 = ContentEdit(1, 2, "Z")

          rope.withEdit(edit1).withEdit(edit2.rebase(edit1)).toString shouldEqual "aZdXYg"
          rope.withEdit(edit2).withEdit(edit1.rebase(edit2)).toString shouldEqual "aZdXYg"
        }

        "insert only" in {
          val rope = Rope("abcdefg")
          val edit1 = ContentEdit(4, 0, "XY")
          val edit2 = ContentEdit(1, 2, "Z")

          rope.withEdit(edit1).withEdit(edit2.rebase(edit1)).toString shouldEqual "aZdXYefg"
          rope.withEdit(edit2).withEdit(edit1.rebase(edit2)).toString shouldEqual "aZdXYefg"
        }

        "delete only" in {
          val rope = Rope("abcdefg")
          val edit1 = ContentEdit(4, 2, "")
          val edit2 = ContentEdit(1, 2, "Z")

          rope.withEdit(edit1).withEdit(edit2.rebase(edit1)).toString shouldEqual "aZdg"
          rope.withEdit(edit2).withEdit(edit1.rebase(edit2)).toString shouldEqual "aZdg"
        }
      }

      "non-intersecting" in {
        val rope = Rope("abcdef")
        val edit1 = ContentEdit(2, 3, "X")
        val edit2 = ContentEdit(3, 2, "Q")

        rope.withEdit(edit1).withEdit(edit2.rebase(edit1)).toString shouldEqual "abXQf"
        rope.withEdit(edit2).withEdit(edit1.rebase(edit2)).toString shouldEqual "abXQf"
      }

      "intersecting" in {
        val rope = Rope("abcdef")
        val edit1 = ContentEdit(2, 3, "X")
        val edit2 = ContentEdit(3, 1, "Q")
        rope.withEdit(edit1).withEdit(edit2.rebase(edit1)).toString shouldEqual "abXQf"
        rope.withEdit(edit2).withEdit(edit1.rebase(edit2)).toString shouldEqual "abXQf"
      }

      "content shortened" in {
        val rope = Rope("abcdef")
        val edit1 = ContentEdit(0, 5, "QRS")
        val edit2 = ContentEdit(4, 1, "Z")

        rope.withEdit(edit1).withEdit(edit2.rebase(edit1)).toString shouldEqual "QRSZf"
        rope.withEdit(edit2).withEdit(edit1.rebase(edit2)).toString shouldEqual "QRSZf"
      }

      "completely replaced" in {
        val rope = Rope("abcdef")
        val edit1 = ContentEdit(0, 6, "P")
        val edit2 = ContentEdit(4, 1, "Z")
        rope.withEdit(edit1).withEdit(edit2.rebase(edit1)).toString shouldEqual "PZ"
        rope.withEdit(edit2).withEdit(edit1.rebase(edit2)).toString shouldEqual "PZ"
      }

      "one contained in the other" in {
        val rope = Rope("abcdef")
        val edit1 = ContentEdit(2, 4, "P")
        val edit2 = ContentEdit(3, 2, "Z")
        rope.withEdit(edit1).withEdit(edit2.rebase(edit1)).toString shouldEqual "abPZ"
        rope.withEdit(edit2).withEdit(edit1.rebase(edit2)).toString shouldEqual "abPZ"
      }
    }

    "content is the same no matter what order edits are applied" in forAll(genEditScenario) {
      case EditScenario(initial, edit, edits) =>
        // TODO: this doesn't pass, because the initial set of edits aren't all independent... it's the baseline that's wrong!

        edits.reverse.tails.toList.reverse.map(_.reverse).foreach {
          edits =>

            val rebasedEdits = edits.foldLeft(Queue(edit)) {
              (rebased, edit) => rebased enqueue rebased.foldLeft(edit)(_ rebase _)
            }.tail.toList

            // apply the edit, then the other edits rebased onto it
            val singleEditFirst = initial.withEdit(edit).withEdits(rebasedEdits)


            // apply the other edits, then apply the edit rebased onto all of them in sequence
            val rebasedEdit = edits.foldLeft(edit)(_ rebase _)
            val singleEditLast = initial.withEdits(edits).withEdit(rebasedEdit)


            println(initial, edits, edit, rebasedEdit, rebasedEdits)
            singleEditFirst.toString shouldEqual singleEditLast.toString

        }


//        // should be equivalent no matter how many edits from the list we apply first, as long as edit is always rebased
//        (1 until edits.size) foreach {
//          i =>
//            val (before, after) = edits.splitAt(i)
//            val rebased = before.foldLeft(edit)(_ rebase _)
//            initial.withEdits(before ::: rebased :: after).toString shouldEqual baseline
//        }

        // rebasing the edit to the end of edits should be the same as rebasing edits after edit
        //initial.withEdits(edits).withEdit(rebasedEdit).toString shouldEqual initial.withEdit(edit).withEdits(rebasedEdits).toString

    }

  }

}
