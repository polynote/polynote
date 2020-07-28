package polynote.server.repository.format.ipynb

import io.circe.parser.parse
import org.scalatest.{FreeSpec, Matchers}
import polynote.config.{PolynoteConfig, SparkConfig}
import polynote.kernel.{Output, ResultValue}
import polynote.messages.{CellID, Notebook, NotebookCell, NotebookConfig, ShortList}
import polynote.runtime.StringRepr
import polynote.server.repository.NotebookContent
import polynote.testing.ConfiguredZIOSpec

class ZeppelinFormatSpec extends FreeSpec with Matchers with ConfiguredZIOSpec {

  override def config: PolynoteConfig = PolynoteConfig(spark = Option(SparkConfig(Map("foo" -> "bar"))))

  private val subject = new ZeppelinToIpynbFormat

  "ZeppelinToIpynbFormat" - {

    "successfully parses a Zeppelin notebook" in {

      val nb = subject.decodeNotebook("dummy",
        """{
          |  "paragraphs": [
          |    {
          |      "text": "some scala code",
          |      "user": "anonymous",
          |      "dateUpdated": "May 26, 2020 5:45:47 AM",
          |      "config": {
          |        "colWidth": 12.0,
          |        "enabled": true,
          |        "results": {},
          |        "editorSetting": {
          |          "language": "scala",
          |          "editOnDblClick": false
          |        },
          |        "editorMode": "ace/mode/scala"
          |      },
          |      "settings": {
          |        "params": {},
          |        "forms": {}
          |      },
          |      "results": {
          |        "code": "SUCCESS",
          |        "msg": [
          |          {
          |            "type": "TEXT",
          |            "data": "some results"
          |          }
          |        ]
          |      },
          |      "apps": [],
          |      "jobName": "paragraph_1495229201127_611751718",
          |      "id": "20170519-212641_1884744885",
          |      "dateCreated": "May 19, 2017 9:26:41 PM",
          |      "dateStarted": "May 26, 2020 5:45:47 AM",
          |      "dateFinished": "May 26, 2020 5:46:14 AM",
          |      "status": "FINISHED",
          |      "progressUpdateIntervalMs": 500
          |    },
          |    {
          |      "text": "more scala code",
          |      "user": "anonymous",
          |      "dateUpdated": "May 26, 2020 5:48:11 AM",
          |      "config": {
          |        "colWidth": 12.0,
          |        "enabled": true,
          |        "results": {},
          |        "editorSetting": {
          |          "language": "scala",
          |          "editOnDblClick": false
          |        },
          |        "editorMode": "ace/mode/scala"
          |      },
          |      "settings": {
          |        "params": {},
          |        "forms": {}
          |      },
          |      "results": {
          |        "code": "SUCCESS",
          |        "msg": [
          |          {
          |            "type": "TEXT",
          |            "data": "more scala results"
          |          }
          |        ]
          |      },
          |      "apps": [],
          |      "jobName": "paragraph_1495229241504_1145275849",
          |      "id": "20170519-212721_1920927243",
          |      "dateCreated": "May 19, 2017 9:27:21 PM",
          |      "dateStarted": "May 26, 2020 5:48:11 AM",
          |      "dateFinished": "May 26, 2020 5:48:12 AM",
          |      "status": "FINISHED",
          |      "progressUpdateIntervalMs": 500
          |    },
          |    {
          |      "user": "anonymous",
          |      "config": {},
          |      "settings": {
          |        "params": {},
          |        "forms": {}
          |      },
          |      "apps": [],
          |      "jobName": "paragraph_1496341154580_1265400641",
          |      "id": "20170601-181914_791841035",
          |      "dateCreated": "Jun 1, 2017 6:19:14 PM",
          |      "status": "READY",
          |      "progressUpdateIntervalMs": 500
          |    }
          |  ],
          |  "name": "My Zeppelin notebook",
          |  "id": "2CJ6M88KH",
          |  "angularObjects": {
          |    "2FBFRHVUX:shared_process": [],
          |    "2FBT2T8NM:shared_process": [],
          |    "2FA5FA21P:shared_process": [],
          |    "2F8EQ2XU8:shared_process": [],
          |    "2FAN6M9VZ:shared_process": [],
          |    "2FA8WGVH6:shared_process": [],
          |    "2FAQKHGDW:shared_process": [],
          |    "2FBM1JV1V:shared_process": [],
          |    "2F8992W5A:shared_process": [],
          |    "2F9ET6TWC:shared_process": [],
          |    "2F92BBM8X:shared_process": [],
          |    "2FBZ7BPUU:shared_process": [],
          |    "2FAHAVUU8:shared_process": [],
          |    "2FAGRU456:shared_process": [],
          |    "2FA7FQ43B:shared_process": [],
          |    "2FAQX645A:shared_process": [],
          |    "2F93URY5Z:shared_process": [],
          |    "2F9F7Y89X:shared_process": []
          |  },
          |  "config": {},
          |  "info": {}
          |}""".stripMargin).runIO()

      nb.cells.head.language shouldEqual "scala"
      nb.cells(1).language shouldEqual "scala"
      nb shouldEqual Notebook("dummy.ipynb", List(
        NotebookCell(0, "scala", "some scala code", List(ResultValue("Out", "", List(StringRepr("some results")), 0, (), scala.reflect.runtime.universe.NoType, None))),
        NotebookCell(1, "scala", "more scala code", List(ResultValue("Out", "", List(StringRepr("more scala results")), 1, (), scala.reflect.runtime.universe.NoType, None))),
        NotebookCell(2, "scala", "", List.empty[ResultValue])
      ),  Option(NotebookConfig.empty.copy(sparkConfig = config.spark.map(SparkConfig.toMap))))

    }
  }

}
