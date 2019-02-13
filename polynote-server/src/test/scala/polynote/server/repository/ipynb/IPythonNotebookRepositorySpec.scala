package polynote.server.repository.ipynb

import io.circe.JsonObject
import org.scalatest.{FreeSpec, Matchers}
import io.circe.syntax._

class IPythonNotebookRepositorySpec extends FreeSpec with Matchers {

  "adds blank cell metadata on encode" in {
    // this is needed for compatibility with jupyter tooling
    val cell = JupyterCell(Code, Some(1), metadata = None, Some("scala"), Nil, None).asJsonObject
    assert(cell("metadata").isDefined)
    cell("metadata").flatMap(_.asObject).get.keys.toList shouldEqual Nil
  }

}
