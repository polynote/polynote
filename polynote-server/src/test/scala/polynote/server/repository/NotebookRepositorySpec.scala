package polynote.server.repository

import java.net.URI
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FreeSpec, Matchers}
import polynote.messages.{Notebook, ShortList, fsNotebook}
import polynote.server.MockServerSpec
import zio.ZIO

class NotebookRepositorySpec extends FreeSpec with Matchers with MockFactory with MockServerSpec {
  private val root = mock[NotebookRepository]
  private val mount1 = mock[NotebookRepository]
  private val mount2 = mock[NotebookRepository]
  private val tr = new TreeRepository(root, Map("one" -> mount1, "two" -> mount2))

  private def emptyNB(path: String) = Notebook(path, ShortList(List.empty), None)

  "A TreeRepository" - {
    "should delegate" - {
      def testDelegate(path: String)(f: (NotebookRepository, String, Option[String]) => Unit): Unit = {
        tr.delegate(path) {
          (repo, relativePath, maybeBasePath) => ZIO(f(repo, relativePath, maybeBasePath))
        }.runIO
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

      "notebookExists" - {
        "for relative paths in the root mount" in {
          (root.notebookExists _).expects("foo").once().returning(ZIO.succeed(true))
          tr.notebookExists("foo").runIO shouldBe true
        }
        "for relative paths in the other mounts" in {
          (mount1.notebookExists _).expects("foo").once().returning(ZIO.succeed(true))
          tr.notebookExists("one/foo").runIO shouldBe true
        }

        "for absolute paths in the root mount" in {
          (root.notebookExists _).expects("foo").once().returning(ZIO.succeed(true))
          tr.notebookExists("/foo").runIO shouldBe true
        }
        "for absolute paths in the other mounts" in {
          (mount1.notebookExists _).expects("foo").once().returning(ZIO.succeed(true))
          tr.notebookExists("/one/foo").runIO shouldBe true
        }
      }
      "notebookURI" - {
        "for relative paths in the root mount" in {
          val nbURI = Some(new URI("/foo"))
          (root.notebookURI _).expects("foo").once().returning(ZIO.succeed(nbURI))
          tr.notebookURI("foo").runIO shouldEqual nbURI
        }
        "for relative paths in the other mounts" in {
          val nbURI = Some(new URI("/one/foo"))
          (mount1.notebookURI _).expects("foo").once().returning(ZIO.succeed(nbURI))
          tr.notebookURI("one/foo").runIO shouldEqual nbURI
        }

        "for absolute paths in the root mount" in {
          val nbURI = Some(new URI("/foo"))
          (root.notebookURI _).expects("foo").once().returning(ZIO.succeed(nbURI))
          tr.notebookURI("/foo").runIO shouldEqual nbURI
        }
        "for absolute paths in the other mounts" in {
          val nbURI = Some(new URI("/one/foo"))
          (mount1.notebookURI _).expects("foo").once().returning(ZIO.succeed(nbURI))
          tr.notebookURI("/one/foo").runIO shouldEqual nbURI
        }
      }
      "loadNotebook" - {
        "for relative paths in the root mount" in {
          val nb = emptyNB("foo")
          (root.loadNotebook _).expects("foo").once().returning(ZIO.succeed(nb))
          tr.loadNotebook("foo").runIO shouldEqual nb
        }
        "for relative paths in the other mounts" in {
          val nb = emptyNB("foo")
          (mount1.loadNotebook _).expects("foo").once().returning(ZIO.succeed(nb))
          tr.loadNotebook("one/foo").runIO shouldEqual nb.copy(path="one/foo")
        }

        "for absolute paths in the root mount" in {
          val nb = emptyNB("foo")
          (root.loadNotebook _).expects("foo").once().returning(ZIO.succeed(nb))
          tr.loadNotebook("/foo").runIO shouldEqual nb
        }
        "for absolute paths in the other mounts" in {
          val nb = emptyNB("foo")
          (mount1.loadNotebook _).expects("foo").once().returning(ZIO.succeed(nb))
          tr.loadNotebook("/one/foo").runIO shouldEqual nb.copy(path="one/foo")
        }
      }
      "createNotebook" - {
        "for relative paths in the root mount" in {
          (root.createNotebook _).expects("foo", None).once().returning(ZIO.succeed("foo"))
          tr.createNotebook("foo", None).runIO shouldEqual "foo"
        }
        "for relative paths in the other mounts" in {
          (mount1.createNotebook _).expects("foo", None).once().returning(ZIO.succeed("foo"))
          tr.createNotebook("one/foo", None).runIO shouldEqual "one/foo"
        }

        "for absolute paths in the root mount" in {
          (root.createNotebook _).expects("foo", None).once().returning(ZIO.succeed("foo"))
          tr.createNotebook("/foo", None).runIO shouldEqual "foo"
        }
        "for absolute paths in the other mounts" in {
          (mount1.createNotebook _).expects("foo", None).once().returning(ZIO.succeed("foo"))
          tr.createNotebook("/one/foo", None).runIO shouldEqual "one/foo"
        }
      }
    }

    "should list all notebooks" in {
      val rootNbs = List(fsNotebook("a", 0), fsNotebook("b", 0), fsNotebook("c", 0))
      val oneNbs = List(fsNotebook("1", 0), fsNotebook("2", 0), fsNotebook("3", 0))
      val twoNBs = List(fsNotebook("!", 0), fsNotebook("@", 0), fsNotebook("#", 0))
      (root.listNotebooks _).expects().once().returning(ZIO.succeed(rootNbs))
      (mount1.listNotebooks _).expects().once().returning(ZIO.succeed(oneNbs))
      (mount2.listNotebooks _).expects().once().returning(ZIO.succeed(twoNBs))

      tr.listNotebooks().runIO should contain theSameElementsAs rootNbs :::
        oneNbs.map(s => fsNotebook(s"one/${s.path}", s.lastSaved)) :::
        twoNBs.map(s => fsNotebook(s"two/${s.path}", s.lastSaved))
    }

    "should rename notebooks" - {
      "within the same mount" - {
        "in root" in {
          (root.renameNotebook _).expects("foo", "bar").once().returning(ZIO.succeed("bar"))
          tr.renameNotebook("foo", "bar").runIO shouldEqual "bar"

          (root.renameNotebook _).expects("foo", "bar").once().returning(ZIO.succeed("bar"))
          tr.renameNotebook("/foo", "/bar").runIO shouldEqual "bar"
        }
        "in a submount" in {
          (mount1.renameNotebook _).expects("foo", "bar").once().returning(ZIO.succeed("bar"))
          tr.renameNotebook("one/foo", "one/bar").runIO shouldEqual "one/bar"

          (mount1.renameNotebook _).expects("foo", "bar").once().returning(ZIO.succeed("bar"))
          tr.renameNotebook("/one/foo", "/one/bar").runIO shouldEqual "one/bar"
        }
      }
      "across different mounts" - {
        "from root to a submount" in {
          val nb = emptyNB("foo")
          (root.loadNotebook _).expects("foo").once().returning(ZIO.succeed(nb))
          (mount1.saveNotebook _).expects(nb.copy(path="bar")).returning(ZIO.unit)
          (root.deleteNotebook _).expects("foo").returning(ZIO.unit)

          tr.renameNotebook("foo", "one/bar").runIO shouldEqual "one/bar"
        }
        "from a submout to root" in {
          val nb = emptyNB("foo")
          (mount1.loadNotebook _).expects("foo").once().returning(ZIO.succeed(nb))
          (root.saveNotebook _).expects(nb.copy(path="bar")).returning(ZIO.unit)
          (mount1.deleteNotebook _).expects("foo").returning(ZIO.unit)

          tr.renameNotebook("one/foo", "bar").runIO shouldEqual "bar"
        }
        "from a submount to another submount" in {
          val nb = emptyNB("foo")
          (mount1.loadNotebook _).expects("foo").once().returning(ZIO.succeed(nb))
          (mount2.saveNotebook _).expects(nb.copy(path="bar")).returning(ZIO.unit)
          (mount1.deleteNotebook _).expects("foo").returning(ZIO.unit)

          tr.renameNotebook("one/foo", "two/bar").runIO shouldEqual "two/bar"
        }
      }

      "should copy notebooks" - {
        "within the same mount" - {
          "in root" in {
            (root.copyNotebook _).expects("foo", "bar").once().returning(ZIO.succeed("bar"))
            tr.copyNotebook("foo", "bar").runIO shouldEqual "bar"

            (root.copyNotebook _).expects("foo", "bar").once().returning(ZIO.succeed("bar"))
            tr.copyNotebook("/foo", "/bar").runIO shouldEqual "bar"
          }
          "in a submount" in {
            (mount1.copyNotebook _).expects("foo", "bar").once().returning(ZIO.succeed("bar"))
            tr.copyNotebook("one/foo", "one/bar").runIO shouldEqual "one/bar"

            (mount1.copyNotebook _).expects("foo", "bar").once().returning(ZIO.succeed("bar"))
            tr.copyNotebook("/one/foo", "/one/bar").runIO shouldEqual "one/bar"
          }
        }
        "across different mounts" - {
          "from root to a submount" in {
            val nb = emptyNB("foo")
            (root.loadNotebook _).expects("foo").once().returning(ZIO.succeed(nb))
            (mount1.saveNotebook _).expects(nb.copy(path="bar")).returning(ZIO.unit)

            tr.copyNotebook("foo", "one/bar").runIO shouldEqual "one/bar"
          }
          "from a submout to root" in {
            val nb = emptyNB("foo")
            (mount1.loadNotebook _).expects("foo").once().returning(ZIO.succeed(nb))
            (root.saveNotebook _).expects(nb.copy(path="bar")).returning(ZIO.unit)

            tr.copyNotebook("one/foo", "bar").runIO shouldEqual "bar"
          }
          "from a submount to another submount" in {
            val nb = emptyNB("foo")
            (mount1.loadNotebook _).expects("foo").once().returning(ZIO.succeed(nb))
            (mount2.saveNotebook _).expects(nb.copy(path="bar")).returning(ZIO.unit)

            tr.copyNotebook("one/foo", "two/bar").runIO shouldEqual "two/bar"
          }
        }
      }
    }
  }
}
