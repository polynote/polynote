package polynote.server.repository

import org.scalamock.scalatest.MockFactory
import org.scalatest.{FreeSpec, Matchers}
import polynote.messages.{Notebook, ShortList}
import polynote.server.MockServerSpec
import zio.ZIO

class NotebookRepositorySpec extends FreeSpec with Matchers with MockFactory with MockServerSpec {
  val root = mock[NotebookRepository]
  val mount1 = mock[NotebookRepository]
  val mount2 = mock[NotebookRepository]
  val tr = new TreeRepository(root, Map("one" -> mount1, "two" -> mount2))


  "A TreeRepository" - {
    "should delegate" - {
      def testDelegate(path: String)(f: (NotebookRepository, String, Option[String]) => Unit): Unit = {
        tr.delegate(path) {
          (repo, relativePath, maybeBasePath) => ZIO(f(repo, relativePath, maybeBasePath))
        }.runIO(testEnv)
      }

      "to root when the path is baseless" in {
        testDelegate("foo") {
          (repo, relativePath, maybeBasePath) =>
            repo shouldEqual root
            relativePath shouldEqual "foo"
            maybeBasePath shouldEqual None
        }
      }

      "to the proper mount point when required" in {
        testDelegate("one/foo") {
          (repo, relativePath, maybeBasePath) =>
            repo shouldEqual mount1
            relativePath shouldEqual "foo"
            maybeBasePath shouldEqual Some("one")
        }

        testDelegate("two/foo") {
          (repo, relativePath, maybeBasePath) =>
            repo shouldEqual mount2
            relativePath shouldEqual "foo"
            maybeBasePath shouldEqual Some("two")
        }

        testDelegate("three/foo") {
          (repo, relativePath, maybeBasePath) =>
            repo shouldEqual root
            relativePath shouldEqual "three/foo"
            maybeBasePath shouldEqual None
        }
      }

      "while stripping out absolute paths" in {
        testDelegate("/foo") {
          (repo, relativePath, maybeBasePath) =>
            repo shouldEqual root
            relativePath shouldEqual "foo"
            maybeBasePath shouldEqual None
        }

        testDelegate("/one/foo") {
          (repo, relativePath, maybeBasePath) =>
            repo shouldEqual mount1
            relativePath shouldEqual "foo"
            maybeBasePath shouldEqual Some("one")
        }
      }

      "functions to the underlying repo" - {
        "for relative paths in the root mount" in {
          (root.notebookExists _).expects("foo").once()
          (mount1.notebookExists _).expects(*).never()
          (mount2.notebookExists _).expects(*).never()

          tr.notebookExists("foo")
        }
        "for relative paths in the other mounts" in {
          (root.notebookExists _).expects(*).never()
          (mount1.notebookExists _).expects("foo").once()
          (mount2.notebookExists _).expects(*).never()

          tr.notebookExists("one/foo")
        }

        "for absolute paths in the root mount" in {
          (root.notebookExists _).expects("foo").once()
          (mount1.notebookExists _).expects(*).never()
          (mount2.notebookExists _).expects(*).never()

          tr.notebookExists("/foo")
        }
        "for absolute paths in the other mounts" in {
          (root.notebookExists _).expects(*).never()
          (mount1.notebookExists _).expects("foo").once()
          (mount2.notebookExists _).expects(*).never()

          tr.notebookExists("/one/foo")
        }
      }
    }

    "should list all notebooks" in {
      val rootNbs = List("a, b, c")
      val oneNbs = List("1", "2", "3")
      val twoNBs = List("!", "@", "#")
      (root.listNotebooks _).expects().once().returning(ZIO.succeed(rootNbs))
      (mount1.listNotebooks _).expects().once().returning(ZIO.succeed(oneNbs))
      (mount2.listNotebooks _).expects().once().returning(ZIO.succeed(twoNBs))

      tr.listNotebooks().runIO(testEnv) should contain theSameElementsAs rootNbs ::: oneNbs.map(s => s"one/$s") ::: twoNBs.map(s => s"two/$s")
    }

    "should rename notebooks" - {
      "within the same mount" - {
        "in root" in {
          (root.renameNotebook _).expects("foo", "bar").once().returning(ZIO.succeed("bar"))
          tr.renameNotebook("foo", "bar").runIO(testEnv) shouldEqual "bar"

          (root.renameNotebook _).expects("foo", "bar").once().returning(ZIO.succeed("bar"))
          tr.renameNotebook("/foo", "/bar").runIO(testEnv) shouldEqual "bar"
        }
        "in a submount" in {
          (mount1.renameNotebook _).expects("foo", "bar").once().returning(ZIO.succeed("bar"))
          tr.renameNotebook("one/foo", "one/bar").runIO(testEnv) shouldEqual "one/bar"

          (mount1.renameNotebook _).expects("foo", "bar").once().returning(ZIO.succeed("bar"))
          tr.renameNotebook("/one/foo", "/one/bar").runIO(testEnv) shouldEqual "one/bar"
        }
      }
      "across different mounts" - {
        "from root to a submount" in {
          val nb = Notebook("foo", ShortList(List.empty), None)
          (root.loadNotebook _).expects("foo").once().returning(ZIO.succeed(nb))
          (mount1.saveNotebook _).expects(nb.copy(path="bar")).returning(ZIO.unit)
          (root.deleteNotebook _).expects("foo").returning(ZIO.unit)

          tr.renameNotebook("foo", "one/bar").runIO(testEnv) shouldEqual "one/bar"
        }
        "from a submout to root" in {
          val nb = Notebook("foo", ShortList(List.empty), None)
          (mount1.loadNotebook _).expects("foo").once().returning(ZIO.succeed(nb))
          (root.saveNotebook _).expects(nb.copy(path="bar")).returning(ZIO.unit)
          (mount1.deleteNotebook _).expects("foo").returning(ZIO.unit)

          tr.renameNotebook("one/foo", "bar").runIO(testEnv) shouldEqual "bar"
        }
        "from a submount to another submount" in {
          val nb = Notebook("foo", ShortList(List.empty), None)
          (mount1.loadNotebook _).expects("foo").once().returning(ZIO.succeed(nb))
          (mount2.saveNotebook _).expects(nb.copy(path="bar")).returning(ZIO.unit)
          (mount1.deleteNotebook _).expects("foo").returning(ZIO.unit)

          tr.renameNotebook("one/foo", "two/bar").runIO(testEnv) shouldEqual "two/bar"
        }
      }
    }
  }
}
