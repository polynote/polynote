package polynote.kernel.util

import cats.effect.{ContextShift, IO}
import org.scalatest.{FreeSpec, Matchers}
import polynote.kernel.ResultValue

import scala.concurrent.ExecutionContext

class NotebookContextSpec extends FreeSpec with Matchers {

  implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  "NotebookContext" - {

    "insert" - {

      "on empty notebook" - {
        "succeeds with no predecessor" in {
          val notebookContext = new NotebookContext()
          val context = CellContext.unsafe(0, None)
          notebookContext.insert(context, None)
          notebookContext.first shouldEqual Some(context)
          notebookContext.last shouldEqual Some(context)
        }

        "fails when predecessor doesn't exist" in {
          val notebookContext = new NotebookContext()
          val context = CellContext.unsafe(0, None)
          a [NoSuchElementException] shouldBe thrownBy {
            notebookContext.insert(context, Some(-1))
          }
        }

        "fails when predecessor has same id as context" in {
          val notebookContext = new NotebookContext()
          val context = CellContext.unsafe(0, None)
          an [IllegalArgumentException] shouldBe thrownBy {
            notebookContext.insert(context, Some(0))
          }
        }
      }

      "on non-empty notebook" - {
        "fails when predecessor doesn't exist" in {
          val notebookContext = new NotebookContext()
          val context1 = CellContext.unsafe(0)
          val context2 = CellContext.unsafe(1)
          notebookContext.insert(context1, None)

          a [NoSuchElementException] shouldBe thrownBy {
            notebookContext.insert(context2, Some(-1))
          }
        }

        "fails when predecessor has same id as context" in {
          val notebookContext = new NotebookContext()
          val context1 = CellContext.unsafe(0)
          val context2 = CellContext.unsafe(1)
          notebookContext.insert(context1, None)

          an [IllegalArgumentException] shouldBe thrownBy {
            notebookContext.insert(context2, Some(1))
          }
        }

        "sets the predecessor on successful insert" in {
          val notebookContext = new NotebookContext()
          val context1 = CellContext.unsafe(0)
          val context2 = CellContext.unsafe(1)
          notebookContext.insert(context1, None)
          notebookContext.insert(context2, Some(0))

          context2.previous shouldEqual Some(context1)

          notebookContext.first shouldEqual Some(context1)
          notebookContext.last shouldEqual Some(context2)
        }
      }
    }

    "insertLast" - {
      "on empty notebook" in {
        val notebookContext = new NotebookContext()
        val context = CellContext.unsafe(0)
        notebookContext.insertLast(context)
        notebookContext.first shouldEqual Some(context)
        notebookContext.last shouldEqual Some(context)
      }

      "on non-empty notebook" in {
        val notebookContext = new NotebookContext()
        val context = CellContext.unsafe(0)
        notebookContext.insertLast(context)

        val context2 = CellContext.unsafe(1)
        notebookContext.insertLast(context2)

        notebookContext.first shouldEqual Some(context)
        notebookContext.last shouldEqual Some(context2)
        context2.previous shouldEqual Some(context)
      }

    }

    "insertFirst" - {
      "on empty notebook" in {
        val notebookContext = new NotebookContext()
        val context = CellContext.unsafe(0)
        notebookContext.insertFirst(context)
        notebookContext.first shouldEqual Some(context)
        notebookContext.last shouldEqual Some(context)
      }

      "on non-empty notebook" in {
        val notebookContext = new NotebookContext()
        val context = CellContext.unsafe(0)
        notebookContext.insertFirst(context)

        val context2 = CellContext.unsafe(1)
        notebookContext.insertFirst(context2)

        notebookContext.first shouldEqual Some(context2)
        notebookContext.last shouldEqual Some(context)
        context.previous shouldEqual Some(context2)
      }
    }

    "remove" - {
      "fails on empty notebook" in {
        val notebookContext = new NotebookContext()
        a [NoSuchElementException] shouldBe thrownBy {
          notebookContext.remove(0)
        }
      }

      "fails when id doesn't exist" in {
        val notebookContext = new NotebookContext()
        val context = CellContext.unsafe(0)
        notebookContext.insertFirst(context)
        a [NoSuchElementException] shouldBe thrownBy {
          notebookContext.remove(1)
        }
      }

      "makes it empty when there are no others" in {
        val notebookContext = new NotebookContext()
        val context = CellContext.unsafe(0)
        notebookContext.insertFirst(context)
        notebookContext.remove(0)
        notebookContext.first shouldEqual None
        notebookContext.last shouldEqual None
      }

      "middle of two contexts" in {
        val notebookContext = new NotebookContext()
        val a = CellContext.unsafe(0)
        val b = CellContext.unsafe(1)
        val c = CellContext.unsafe(2)

        notebookContext.insertLast(a)
        notebookContext.insertLast(b)
        notebookContext.insertLast(c)

        notebookContext.remove(1)

        c.previous shouldEqual Some(a)
        a.previous shouldEqual None
        notebookContext.last shouldEqual Some(c)
      }

      "last context" in {
        val notebookContext = new NotebookContext()
        val a = CellContext.unsafe(0)
        val b = CellContext.unsafe(1)
        val c = CellContext.unsafe(2)

        notebookContext.insertLast(a)
        notebookContext.insertLast(b)
        notebookContext.insertLast(c)

        notebookContext.remove(2)

        notebookContext.last shouldEqual Some(b)
        b.previous shouldEqual Some(a)
        a.previous shouldEqual None
      }

      "first context" in {
        val notebookContext = new NotebookContext()
        val a = CellContext.unsafe(0)
        val b = CellContext.unsafe(1)
        val c = CellContext.unsafe(2)

        notebookContext.insertLast(a)
        notebookContext.insertLast(b)
        notebookContext.insertLast(c)

        notebookContext.remove(0)

        notebookContext.last shouldEqual Some(c)
        notebookContext.first shouldEqual Some(a)
        c.previous shouldEqual Some(b)
        b.previous shouldEqual None
      }
    }

    "find" - {
      "returns none when predicate isn't satisfied" in {
        val notebookContext = new NotebookContext()
        val a = CellContext.unsafe(0)
        val b = CellContext.unsafe(1)
        val c = CellContext.unsafe(2)
        notebookContext.insertLast(a)
        notebookContext.insertLast(b)
        notebookContext.insertLast(c)

        notebookContext.find(_.id == 100) shouldEqual None
      }

      "returns last element that satisfies the predicate" in {
        val notebookContext = new NotebookContext()
        val a = CellContext.unsafe(0)
        val b = CellContext.unsafe(1)
        val c = CellContext.unsafe(2)
        notebookContext.insertLast(a)
        notebookContext.insertLast(b)
        notebookContext.insertLast(c)

        notebookContext.find(_.id < 100) shouldEqual Some(c)
        notebookContext.find(_.id < 2) shouldEqual Some(b)
      }
    }

    "lastScope" in {
      val notebookContext = new NotebookContext()
      val a = CellContext.unsafe(0)
      val b = CellContext.unsafe(1)
      val c = CellContext.unsafe(2)
      notebookContext.insertLast(a)
      notebookContext.insertLast(b)
      notebookContext.insertLast(c)

      import scala.reflect.runtime.universe.NoType
      import fs2.Stream

      val a_a1 = new ResultValue("a1", "", Nil, 0, "a_a1", NoType, None)
      val a_a2 = new ResultValue("a2", "", Nil, 0, "a_a2", NoType, None)
      val b_b1 = new ResultValue("b1", "", Nil, 1, "b_b1", NoType, None)
      val c_c1 = new ResultValue("c1", "", Nil, 2, "c_c1", NoType, None)
      val c_a2 = new ResultValue("a2", "", Nil, 2, "c_a2", NoType, None)

      Stream.emits(Seq(a_a1, a_a2)).through(a.results.enqueue).compile.drain.unsafeRunSync()
      Stream.emit(b_b1).through(b.results.enqueue).compile.drain.unsafeRunSync()
      Stream.emits(Seq(c_c1, c_a2)).through(c.results.enqueue).compile.drain.unsafeRunSync()

      notebookContext.lastScope should contain theSameElementsAs List(a_a1, b_b1, c_c1, c_a2)
    }

  }

}
