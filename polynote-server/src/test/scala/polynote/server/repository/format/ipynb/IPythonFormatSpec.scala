package polynote.server.repository.format.ipynb

import io.circe.parser.parse
import org.scalatest.{FreeSpec, Matchers}
import polynote.messages.{NotebookCell, CellID}
import polynote.server.repository.NotebookContent
import polynote.testing.ZIOSpec

class IPythonFormatSpec extends FreeSpec with Matchers with ZIOSpec {

  private val subject = new IPythonFormat

  "IPythonFormat" - {

    "applies notebook language when no cell language is present" in {

      val nb = subject.decodeNotebook("dummy",
        """{
          | "metadata": {
          |   "language_info": {
          |     "name": "python"
          |   }
          | },
          | "nbformat": 4,
          | "nbformat_minor": 0,
          | "cells": [
          |   {
          |     "cell_type": "code",
          |     "execution_count": 0,
          |     "source": [
          |       "some python code"
          |     ],
          |     "outputs": []
          |   },
          |   {
          |     "cell_type": "code",
          |     "execution_count": 1,
          |     "metadata": {
          |       "language": "scala"
          |     },
          |     "source": [
          |       "some scala code"
          |     ],
          |     "outputs": []
          |   }
          | ]
          |}""".stripMargin).runIO()

      nb.cells.head.language shouldEqual "python"
      nb.cells(1).language shouldEqual "scala"

    }

    "Saves language_info with most-used language from notebook" in {
      val encoded = subject.encodeNotebook(NotebookContent(
        List(
          NotebookCell(CellID(0), "aaa", "aaa"),
          NotebookCell(CellID(1), "ccc", "ccc"),
          NotebookCell(CellID(2), "bbb", "bbb"),
          NotebookCell(CellID(3), "aaa", "aaa")),
        None
      )).runIO()

      val langInfoName = parse(encoded).right.get.hcursor
        .downField("metadata")
        .downField("language_info")
        .downField("name")
        .as[String]

      langInfoName shouldEqual Right("aaa")
    }

  }

}
