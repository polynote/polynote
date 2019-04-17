package polynote.server.repository.ipynb

import cats.effect.IO
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.{FlatSpec, Matchers}
import polynote.server.repository.ipynb.JupyterOutput.{DisplayData, Error, ExecuteResult}
import polynote.server.repository.ipynb.ZeppelinResultCode.{ERROR, SUCCESS}
import polynote.server.repository.ipynb.ZeppelinResultType.{HTML, TABLE, TEXT}
import polynote.server.repository.ipynb.ZeppelinStatus.{FINISHED, READY}

class ZeppelinConverterSpec extends FlatSpec with Matchers {

  val json =
    """
      |{
      |  "paragraphs": [
      |    {
      |      "text": "%md\n# this is a markdown cell\nmaybe it will look nice",
      |      "user": "anonymous",
      |      "dateUpdated": "2019-04-12T01:52:41+0000",
      |      "config": {
      |        "colWidth": 12,
      |        "enabled": true,
      |        "results": {},
      |        "editorSetting": {
      |          "language": "markdown",
      |          "editOnDblClick": true
      |        },
      |        "editorMode": "ace/mode/markdown",
      |        "editorHide": true,
      |        "tableHide": false
      |      },
      |      "settings": {
      |        "params": {},
      |        "forms": {}
      |      },
      |      "results": {
      |        "code": "SUCCESS",
      |        "msg": [
      |          {
      |            "type": "HTML",
      |            "data": "<div class=\"markdown-body\">\n<h1>this is a markdown cell</h1>\n<p>maybe it will look nice</p>\n</div>"
      |          }
      |        ]
      |      },
      |      "apps": [],
      |      "jobName": "paragraph_1555033706238_-721290294",
      |      "id": "20190412-014826_2104405465",
      |      "dateCreated": "2019-04-12T01:48:26+0000",
      |      "dateStarted": "2019-04-12T01:52:41+0000",
      |      "dateFinished": "2019-04-12T01:52:41+0000",
      |      "status": "FINISHED",
      |      "progressUpdateIntervalMs": 500,
      |      "focus": true,
      |      "$$hashKey": "object:7181"
      |    },
      |    {
      |      "text": "import spark.implicits._\nval x = 1\nspark\n",
      |      "user": "anonymous",
      |      "dateUpdated": "2019-04-12T01:52:41+0000",
      |      "config": {
      |        "colWidth": 12,
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
      |            "data": "import spark.implicits._\nx: Int = 1\nres0: org.apache.spark.sql.SparkSession = org.apache.spark.sql.SparkSession@5ecb098c\n"
      |          }
      |        ]
      |      },
      |      "apps": [],
      |      "jobName": "paragraph_1555033846345_595487364",
      |      "id": "20190412-015046_634464930",
      |      "dateCreated": "2019-04-12T01:50:46+0000",
      |      "dateStarted": "2019-04-12T01:52:41+0000",
      |      "dateFinished": "2019-04-12T01:53:00+0000",
      |      "status": "FINISHED",
      |      "progressUpdateIntervalMs": 500,
      |      "$$hashKey": "object:7182"
      |    },
      |    {
      |      "text": "z.show(spark.sparkContext.parallelize(Seq(1,2,3,4).map(x => (x, x*2))).toDF)",
      |      "user": "anonymous",
      |      "dateUpdated": "2019-04-15T18:06:10+0000",
      |      "config": {
      |        "colWidth": 12,
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
      |        "code": "ERROR",
      |        "msg": [
      |          {
      |            "type": "TEXT",
      |            "data": "org.apache.zeppelin.interpreter.InterpreterException: java.lang.reflect.InvocationTargetException\n  at org.apache.zeppelin.spark.ZeppelinContext.showDF(ZeppelinContext.java:239)\n  at org.apache.zeppelin.spark.ZeppelinContext.show(ZeppelinContext.java:206)\n  at org.apache.zeppelin.spark.ZeppelinContext.show(ZeppelinContext.java:193)\n  ... 49 elided\nCaused by: java.lang.reflect.InvocationTargetException: org.apache.spark.SparkException: Job aborted due to stage failure: Task 0 in stage 3.0 failed 4 times, most recent failure: Lost task 0.3 in stage 3.0 (TID 11, 100.82.173.203, executor 3): java.io.IOException: Failed to create local dir in /mnt/tmp/spark/blockmgr-4f97c9ac-8f7b-4e95-bfad-8652fa30b89c/0b.\n\tat org.apache.spark.storage.DiskBlockManager.getFile(DiskBlockManager.scala:70)\n\tat org.apache.spark.storage.DiskStore.contains(DiskStore.scala:124)\n\tat org.apache.spark.storage.BlockManager.getLocalValues(BlockManager.scala:472)\n\tat org.apache.spark.broadcast.TorrentBroadcast$$anonfun$readBroadcastBlock$1.apply(TorrentBroadcast.scala:210) ...elided..."
      |          }
      |        ]
      |      },
      |      "apps": [],
      |      "jobName": "paragraph_1555033877781_18794735",
      |      "id": "20190412-015117_473576794",
      |      "dateCreated": "2019-04-12T01:51:17+0000",
      |      "dateStarted": "2019-04-15T18:06:10+0000",
      |      "dateFinished": "2019-04-15T18:06:11+0000",
      |      "status": "ERROR",
      |      "progressUpdateIntervalMs": 500,
      |      "$$hashKey": "object:7183"
      |    },
      |    {
      |      "user": "anonymous",
      |      "dateUpdated": "2019-04-15T18:06:32+0000",
      |      "config": {
      |        "colWidth": 12,
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
      |      "apps": [],
      |      "jobName": "paragraph_1555033910032_-614639872",
      |      "id": "20190412-015150_2140621408",
      |      "dateCreated": "2019-04-12T01:51:50+0000",
      |      "status": "FINISHED",
      |      "progressUpdateIntervalMs": 500,
      |      "$$hashKey": "object:7184",
      |      "text": "z.show(spark.sparkContext.parallelize(Seq(1,2,3,4).map(x => (x, x*2))).toDF)",
      |      "dateFinished": "2019-04-15T18:06:55+0000",
      |      "dateStarted": "2019-04-15T18:06:32+0000",
      |      "results": {
      |        "code": "SUCCESS",
      |        "msg": [
      |          {
      |            "type": "TABLE",
      |            "data": "_1\t_2\n1\t2\n2\t4\n3\t6\n4\t8\n"
      |          }
      |        ]
      |      }
      |    },
      |    {
      |      "user": "anonymous",
      |      "config": {
      |        "colWidth": 12,
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
      |      "apps": [],
      |      "jobName": "paragraph_1555352369947_685954456",
      |      "id": "20190415-181929_1484410463",
      |      "dateCreated": "2019-04-15T18:19:29+0000",
      |      "status": "FINISHED",
      |      "progressUpdateIntervalMs": 500,
      |      "focus": true,
      |      "$$hashKey": "object:8337",
      |      "text": "println(\"%html <i>italics!</i>\")",
      |      "dateUpdated": "2019-04-15T18:20:27+0000",
      |      "dateFinished": "2019-04-15T18:20:27+0000",
      |      "dateStarted": "2019-04-15T18:20:27+0000",
      |      "results": {
      |        "code": "SUCCESS",
      |        "msg": [
      |          {
      |            "type": "HTML",
      |            "data": "<i>italics!</i>\n"
      |          }
      |        ]
      |      }
      |    },
      |    {
      |      "user": "anonymous",
      |      "config": {
      |        "colWidth": 12,
      |        "enabled": true,
      |        "results": {},
      |        "editorSetting": {
      |          "language": "python",
      |          "editOnDblClick": false
      |        },
      |        "editorMode": "ace/mode/python"
      |      },
      |      "settings": {
      |        "params": {},
      |        "forms": {}
      |      },
      |      "apps": [],
      |      "jobName": "paragraph_1555351592828_-390688458",
      |      "id": "20190415-180632_1651957362",
      |      "dateCreated": "2019-04-15T18:06:32+0000",
      |      "status": "FINISHED",
      |      "progressUpdateIntervalMs": 500,
      |      "focus": true,
      |      "$$hashKey": "object:8171",
      |      "text": "%pyspark\nfrom pylab import figure, show, rand\nfrom matplotlib.patches import Ellipse\nimport matplotlib.pyplot as plt\n# helper function to display in Zeppelin\n\nimport StringIO\ndef show(p):\n  img = StringIO.StringIO()\n  p.savefig(img, format='svg')\n  img.seek(0)\n  print \"%html <div style='width:600px'>\" + img.buf + \"</div>\"",
      |      "dateUpdated": "2019-04-15T18:21:19+0000",
      |      "dateFinished": "2019-04-15T18:21:20+0000",
      |      "dateStarted": "2019-04-15T18:21:19+0000",
      |      "results": {
      |        "code": "SUCCESS",
      |        "msg": []
      |      }
      |    },
      |    {
      |      "text": "%pyspark\nimport matplotlib.pyplot as plt\n\nplt.clf()\n#define some data\nx = [1,2,3,4]\ny = [20, 21, 20.5, 20.8]\n\n#plot data\nplt.plot(x, y, marker=\"o\", color=\"red\")\nshow(plt)\n",
      |      "user": "anonymous",
      |      "dateUpdated": "2019-04-15T18:21:33+0000",
      |      "config": {
      |        "colWidth": 12,
      |        "enabled": true,
      |        "results": {},
      |        "editorSetting": {
      |          "language": "python",
      |          "editOnDblClick": false
      |        },
      |        "editorMode": "ace/mode/python"
      |      },
      |      "settings": {
      |        "params": {},
      |        "forms": {}
      |      },
      |      "apps": [],
      |      "jobName": "paragraph_1555352479394_-1237219761",
      |      "id": "20190415-182119_988141027",
      |      "dateCreated": "2019-04-15T18:21:19+0000",
      |      "status": "FINISHED",
      |      "progressUpdateIntervalMs": 500,
      |      "focus": true,
      |      "$$hashKey": "object:8489",
      |      "dateFinished": "2019-04-15T18:21:33+0000",
      |      "dateStarted": "2019-04-15T18:21:33+0000",
      |      "results": {
      |        "code": "SUCCESS",
      |        "msg": [
      |          {
      |            "type": "HTML",
      |            "data": "<svg elided...>"
      |          }
      |        ]
      |      }
      |    },
      |    {
      |      "text": "%pyspark\n",
      |      "user": "anonymous",
      |      "dateUpdated": "2019-04-15T18:21:33+0000",
      |      "config": {
      |        "colWidth": 12,
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
      |      "apps": [],
      |      "jobName": "paragraph_1555352493457_-1712384652",
      |      "id": "20190415-182133_1101784054",
      |      "dateCreated": "2019-04-15T18:21:33+0000",
      |      "status": "READY",
      |      "progressUpdateIntervalMs": 500,
      |      "focus": true,
      |      "$$hashKey": "object:8554"
      |    }
      |  ],
      |  "name": "Test notebook for conversion to polynote",
      |  "id": "2EB4XRXRW",
      |  "angularObjects": {
      |    "2E39RXCAA:shared_process": [],
      |    "2E4PWD2CS:shared_process": [],
      |    "2E4UAZSNU:shared_process": [],
      |    "2E4VFMX2W:shared_process": [],
      |    "2E36NXZZJ:shared_process": [],
      |    "2E6QUDJDE:shared_process": [],
      |    "2E6M9SPJ7:shared_process": [],
      |    "2E6N5UFS1:shared_process": [],
      |    "2E5WU94N1:shared_process": [],
      |    "2E5EUEH7Z:shared_process": [],
      |    "2E2S3M59U:shared_process": [],
      |    "2E4DG8UA6:shared_process": [],
      |    "2E4DJRKD8:shared_process": [],
      |    "2E62ZCAK3:shared_process": [],
      |    "2E6Q2XWUC:shared_process": [],
      |    "2E3G73ZP3:shared_process": [],
      |    "2E4KGXJGV:shared_process": [],
      |    "2E4MUZ5Z2:shared_process": []
      |  },
      |  "config": {
      |    "looknfeel": "default",
      |    "personalizedMode": "false"
      |  },
      |  "info": {}
      |}
    """.stripMargin

  val parsedNotebookIO = for {
    parsed  <- IO.fromEither(parse(json))
    staged  <- IO.fromEither(parsed.as[ZeppelinNotebook])
  } yield {
    staged
  }

  val zepNB = parsedNotebookIO.unsafeRunSync()

  "Zeppelin Converters" should "be able to parse a zeppelin file" in {

    val expected = ZeppelinNotebook(
      List(
        ZeppelinParagraph(
          Some(
            """%md
              |# this is a markdown cell
              |maybe it will look nice""".stripMargin),
          Some(ZeppelinResult(
            SUCCESS,
            List(ZeppelinOutput(HTML,
              """<div class="markdown-body">
                |<h1>this is a markdown cell</h1>
                |<p>maybe it will look nice</p>
                |</div>""".stripMargin)))),
          FINISHED, "markdown"),
        ZeppelinParagraph(Some(
          """import spark.implicits._
            |val x = 1
            |spark
            |""".stripMargin),
          Some(ZeppelinResult(
            SUCCESS,List(ZeppelinOutput(TEXT,
              """import spark.implicits._
                |x: Int = 1
                |res0: org.apache.spark.sql.SparkSession = org.apache.spark.sql.SparkSession@5ecb098c
                |""".stripMargin)))),
          FINISHED, "scala"),
        ZeppelinParagraph(Some("z.show(spark.sparkContext.parallelize(Seq(1,2,3,4).map(x => (x, x*2))).toDF)"),
          Some(ZeppelinResult(ERROR,List(
            ZeppelinOutput(TEXT,
              """org.apache.zeppelin.interpreter.InterpreterException: java.lang.reflect.InvocationTargetException
                |  at org.apache.zeppelin.spark.ZeppelinContext.showDF(ZeppelinContext.java:239)
                |  at org.apache.zeppelin.spark.ZeppelinContext.show(ZeppelinContext.java:206)
                |  at org.apache.zeppelin.spark.ZeppelinContext.show(ZeppelinContext.java:193)
                |  ... 49 elided
                |Caused by: java.lang.reflect.InvocationTargetException: org.apache.spark.SparkException: Job aborted due to stage failure: Task 0 in stage 3.0 failed 4 times, most recent failure: Lost task 0.3 in stage 3.0 (TID 11, 100.82.173.203, executor 3): java.io.IOException: Failed to create local dir in /mnt/tmp/spark/blockmgr-4f97c9ac-8f7b-4e95-bfad-8652fa30b89c/0b.
                |	at org.apache.spark.storage.DiskBlockManager.getFile(DiskBlockManager.scala:70)
                |	at org.apache.spark.storage.DiskStore.contains(DiskStore.scala:124)
                |	at org.apache.spark.storage.BlockManager.getLocalValues(BlockManager.scala:472)
                |	at org.apache.spark.broadcast.TorrentBroadcast$$anonfun$readBroadcastBlock$1.apply(TorrentBroadcast.scala:210) ...elided...""".stripMargin)))), ZeppelinStatus.ERROR,"scala"),
        ZeppelinParagraph(Some("z.show(spark.sparkContext.parallelize(Seq(1,2,3,4).map(x => (x, x*2))).toDF)"),
          Some(ZeppelinResult(SUCCESS, List(ZeppelinOutput(TABLE, "_1\t_2\n1\t2\n2\t4\n3\t6\n4\t8\n")))), FINISHED, "scala"),
        ZeppelinParagraph(Some("""println("%html <i>italics!</i>")"""),
          Some(ZeppelinResult(SUCCESS, List(ZeppelinOutput(HTML,"<i>italics!</i>\n")))),FINISHED, "scala"),
        ZeppelinParagraph(Some(
          """%pyspark
            |from pylab import figure, show, rand
            |from matplotlib.patches import Ellipse
            |import matplotlib.pyplot as plt
            |# helper function to display in Zeppelin
            |
            |import StringIO
            |def show(p):
            |  img = StringIO.StringIO()
            |  p.savefig(img, format='svg')
            |  img.seek(0)
            |  print "%html <div style='width:600px'>" + img.buf + "</div>"""".stripMargin),Some(ZeppelinResult(SUCCESS,List())),FINISHED, "python"),
        ZeppelinParagraph(Some(
          """%pyspark
            |import matplotlib.pyplot as plt
            |
            |plt.clf()
            |#define some data
            |x = [1,2,3,4]
            |y = [20, 21, 20.5, 20.8]
            |
            |#plot data
            |plt.plot(x, y, marker="o", color="red")
            |show(plt)
            |""".stripMargin),Some(ZeppelinResult(SUCCESS,List(ZeppelinOutput(HTML,"<svg elided...>")))),FINISHED,"python"),
        ZeppelinParagraph(Some("%pyspark\n".stripMargin), None, READY, "scala")
      ),
      "Test notebook for conversion to polynote"
    )

    zepNB shouldEqual expected

  }

  it should "convert to a Jupyter notebook" in {
    val jupNB = zepNB.toJupyterNotebook

    jupNB shouldEqual JupyterNotebook(None,4,0,List(
      JupyterCell(Markdown,None,None,Some("markdown"), List("# this is a markdown cell\n", "maybe it will look nice"), None),
      JupyterCell(Code,None,None,Some("scala"),
        List("import spark.implicits._\n", "val x = 1\n", "spark\n"),
        Some(List(ExecuteResult(0,Map("text/plain" -> Json.arr(List("import spark.implicits._\n", "x: Int = 1\n", "res0: org.apache.spark.sql.SparkSession = org.apache.spark.sql.SparkSession@5ecb098c\n").map(Json.fromString): _*)), None)))),
      JupyterCell(Code,None,None,Some("scala"),
        List("z.show(spark.sparkContext.parallelize(Seq(1,2,3,4).map(x => (x, x*2))).toDF)"),
      Some(List(Error("org.apache.zeppelin.interpreter.InterpreterException", "java.lang.reflect.InvocationTargetException",List(
        "  at org.apache.zeppelin.spark.ZeppelinContext.showDF(ZeppelinContext.java:239)",
        "  at org.apache.zeppelin.spark.ZeppelinContext.show(ZeppelinContext.java:206)",
        "  at org.apache.zeppelin.spark.ZeppelinContext.show(ZeppelinContext.java:193)",
        "  ... 49 elided",
        "Caused by: java.lang.reflect.InvocationTargetException: org.apache.spark.SparkException: Job aborted due to stage failure: Task 0 in stage 3.0 failed 4 times, most recent failure: Lost task 0.3 in stage 3.0 (TID 11, 100.82.173.203, executor 3): java.io.IOException: Failed to create local dir in /mnt/tmp/spark/blockmgr-4f97c9ac-8f7b-4e95-bfad-8652fa30b89c/0b.",
        "  at org.apache.spark.storage.DiskBlockManager.getFile(DiskBlockManager.scala:70)",
        "  at org.apache.spark.storage.DiskStore.contains(DiskStore.scala:124)",
        "  at org.apache.spark.storage.BlockManager.getLocalValues(BlockManager.scala:472)",
        "  at org.apache.spark.broadcast.TorrentBroadcast$$anonfun$readBroadcastBlock$1.apply(TorrentBroadcast.scala:210) ...elided..."))))),
    JupyterCell(Code,None,None,Some("scala"),
      List("z.show(spark.sparkContext.parallelize(Seq(1,2,3,4).map(x => (x, x*2))).toDF)"),
      Some(List(DisplayData(Map("text/html" -> Json.arr(Json.fromString("<table><tr><th>_1</th><th>_2</th></tr><tr><td>1</td><td>2</td></tr><tr><td>2</td><td>4</td></tr><tr><td>3</td><td>6</td></tr><tr><td>4</td><td>8</td></tr></table>"))), None)))),
    JupyterCell(Code,None,None,Some("scala"),
      List("""println("%html <i>italics!</i>")"""),
      Some(List(ExecuteResult(0,Map("text/html" -> Json.fromString("<i>italics!</i>\n")),None)))),
    JupyterCell(Code,None,None,Some("python"),
      List(
        "from pylab import figure, show, rand\n",
        "from matplotlib.patches import Ellipse\n",
        "import matplotlib.pyplot as plt\n",
        "# helper function to display in Zeppelin\n",
        "\n",
        "import StringIO\n",
        "def show(p):\n",
        "  img = StringIO.StringIO()\n",
        "  p.savefig(img, format='svg')\n",
        "  img.seek(0)\n",
        "  print \"%html <div style='width:600px'>\" + img.buf + \"</div>\""),
      Some(List())),
    JupyterCell(Code,None,None,Some("python"),
      List(
        "import matplotlib.pyplot as plt\n",
        "\n",
        "plt.clf()\n",
        "#define some data\n",
        "x = [1,2,3,4]\n",
        "y = [20, 21, 20.5, 20.8]\n",
        "\n",
        "#plot data\n",
        "plt.plot(x, y, marker=\"o\", color=\"red\")\n",
        "show(plt)\n"),
      Some(List(ExecuteResult(0,Map("text/html" -> Json.fromString("<svg elided...>")),None)))),
    JupyterCell(Code,None,None,Some("scala"),List(),None)))
  }
}
