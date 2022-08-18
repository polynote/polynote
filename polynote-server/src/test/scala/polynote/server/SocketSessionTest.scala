package polynote.server

import org.scalatest.Matchers
import org.scalatest.FreeSpec
import polynote.server.SocketSession.getContent
import zio.ZIO

import java.io.FileNotFoundException

class SocketSessionTest extends FreeSpec with Matchers {
  "SocketSession" - {
    "getContent" - {
      "with maybeTemplatePath" in {
        getContent(Some("content"), None) shouldEqual ZIO.succeed(Some("content"))
      }

      "fails with bad maybeTemplatePath" in {
        a [FileNotFoundException] should be thrownBy {
          getContent(None, Some("some_bad_path"))
        }
      }
    }
  }
}
