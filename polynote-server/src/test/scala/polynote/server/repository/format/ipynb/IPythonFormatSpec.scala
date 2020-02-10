package polynote.server.repository.format.ipynb

import org.scalatest.{FreeSpec, Matchers}
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

  }

}
