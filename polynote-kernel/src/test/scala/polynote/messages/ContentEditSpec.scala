package polynote.messages

import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FreeSpec, Matchers}
import polynote.data.Rope
import polynote.test.Generators

class ContentEditSpec extends FreeSpec with Matchers with GeneratorDrivenPropertyChecks {

  case class EditScenario(initial: Rope, edit: ContentEdit, edits: List[ContentEdit])

  private val genEditScenario = for {
    (initial, edits, result) <- Generators.genEdits if edits.size > 1
    pivotIndex               <- Gen.choose(1, edits.size - 1)
    (before, after)           = edits.splitAt(pivotIndex)
    beforePivot               = before.foldLeft(initial)(_ withEdit _)
    if beforePivot.size > 0
    edit                     <- Generators.genEdit(beforePivot.size)
  } yield EditScenario(beforePivot, edit, after)

  "rebase" - {

    "quick tests" - {

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
        // TODO: this doesn't pass, because the initial set of edits aren't all independent...
        // apply the edit, then the other edits
        val baseline = initial.withEdits(edit :: edits).toString

        // should be equivalent no matter how many edits from the list we apply first, as long as edit is always rebased
        (1 until edits.size) foreach {
          i =>
            val (before, after) = edits.splitAt(i)
            val rebased = before.foldLeft(edit)(_ rebase _)
            initial.withEdits(before ::: rebased :: after).toString shouldEqual baseline
        }

        // rebasing the edit to the end of edits should be the same as rebasing edits after edit
        //initial.withEdits(edits).withEdit(rebasedEdit).toString shouldEqual initial.withEdit(edit).withEdits(rebasedEdits).toString

    }

  }

}
