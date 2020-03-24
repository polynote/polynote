package polynote.kernel.interpreter.python

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

import org.apache.spark.sql.{AnalysisException, SparkSession}
import org.scalatest.{BeforeAndAfterEach, FreeSpec, Matchers}
import polynote.kernel.ScalaCompiler
import polynote.kernel.environment.Env
import polynote.kernel.interpreter.{Interpreter, State}
import polynote.runtime.python.PythonObject
import polynote.testing.InterpreterSpec
import polynote.testing.kernel.MockEnv
import zio.ZLayer
import zio.blocking.Blocking

import scala.reflect.io.PlainDirectory
import scala.tools.nsc.io.{AbstractFile, Directory}

// Note that as we have to deal with stateful statics like the org.apache.spark.repl.Main SparkSession and PySpark,
// this test will likely never run in parallel.
class PySparkInterpreterSpec extends FreeSpec with InterpreterSpec with Matchers {
  override lazy val outDir: AbstractFile = new PlainDirectory(new Directory(org.apache.spark.repl.Main.outputDir))

  val spark: SparkSession = {
    import org.apache.spark.repl.Main
    Main.conf.setAppName("Polynote tests")
    Main.conf.setMaster("local[*]")
    Main.conf.set("spark.driver.host", "127.0.0.1")
    Main.createSparkSession()
  }

  // due to pyspark unfortunately the PySparkInterpreter is stateful, so we need a new one each time...
  val interpreterRef = new AtomicReference[PySparkInterpreter]()
  val initialStateRef = new AtomicReference[State]()
  override def cellState: State = State.id(1, initialStateRef.get())
  override def interpreter: Interpreter = interpreterRef.get()

  // we dont use BeforeAndAfterEach because some tests need to do stuff before initialization
  def initialize(f: => Unit = ()): Unit = {
    if (interpreterRef.get() != null) {
      interpreter.shutdown().runIO()
    }
    interpreterRef.set(PySparkInterpreter(None).provideSomeLayer[Blocking](ZLayer.succeed(compiler)).runIO())

    f

    initialStateRef.set(interpreter.init(State.Root).provideSomeLayer(MockEnv.layer(State.Root.id + 1)).runIO())
  }

  "The PySpark Kernel" - {

    "properly run simple spark jobs" in {
      initialize()
      val code =
        """
          |def f(x):
          |    return x * 2
          |
          |def add(x, y):
          |    return x + y
          |
          |total = sc.parallelize(range(1, 100)).map(f).reduce(add)
        """.stripMargin
      assertOutput(code) {
        case (vars, output) =>
          vars("total").asInstanceOf[Long] shouldEqual 9900
      }
    }

    "have access to the `kernel` instance" in {
      initialize()
      val code =
        """
          |k_class = str(kernel.getClass())
          |kernel.display.text("hello world")
          |""".stripMargin
      assertOutput(code) {
        case (vars, output) =>
          vars("k_class").asInstanceOf[String] should not be (null)
      }
    }


    "should find the python executable from the config" - {
      "when defaults are used" in {
        initialize()
        assertOutput(
          """
            |import os
            |py = os.environ["PYSPARK_PYTHON"]
            |driverpy = os.environ["PYSPARK_DRIVER_PYTHON"]
            |""".stripMargin){
          case (vars, output) =>
            vars("py") shouldEqual "python3"
            vars("driverpy") shouldEqual "python3"
        }
      }
      "when only spark.pyspark.python is set" in {
        import org.apache.spark.repl.Main
        initialize {
          Main.conf.set("spark.pyspark.python", "blahblah")
        }

        assertOutput(
          """
            |import os
            |py = os.environ["PYSPARK_PYTHON"]
            |driverpy = os.environ["PYSPARK_DRIVER_PYTHON"]
            |""".stripMargin){
          case (vars, output) =>
            vars("py") shouldEqual "blahblah"
            vars("driverpy") shouldEqual "blahblah"
        }
        Main.conf.remove("spark.pyspark.python")
      }
      "when only spark.pyspark.driver.python is set" in {
        import org.apache.spark.repl.Main
        initialize {
          Main.conf.set("spark.pyspark.driver.python", "blahblah")
        }

        assertOutput(
          """
            |import os
            |py = os.environ["PYSPARK_PYTHON"]
            |driverpy = os.environ["PYSPARK_DRIVER_PYTHON"]
            |""".stripMargin){
          case (vars, output) =>
            vars("py") shouldEqual "python3"
            vars("driverpy") shouldEqual "blahblah"
        }
        Main.conf.remove("spark.pyspark.driver.python")
      }

      "failing if misconfigured" in {
        import org.apache.spark.repl.Main
        initialize {
          Main.conf.set("spark.pyspark.python", "blahblah")
        }

        a[RuntimeException] should be thrownBy assertOutput("sc.parallelize(range(1, 2)).count()"){ case _ => }
        Main.conf.remove("spark.pyspark.python")
        Main.conf.remove("spark.pyspark.driver.python")
      }

      "respecting the proper precedence" - {
        import org.apache.spark.repl.Main

        def precedenceTest(tasks: Seq[Int], expectations: Map[String, String]): Unit = {

          val setupTasks = Seq(
            () => Main.conf.set("spark.pyspark.driver.python", "one"),
            () => Main.conf.set("spark.pyspark.python", "two"),
            () => sys.props.put("PYSPARK_DRIVER_PYTHON", "three"),
            () => sys.props.put("PYSPARK_PYTHON", "four"))

          initialize {
            tasks.foreach(i => setupTasks(i)())
          }

          assertOutput(
            """
              |import os
              |py = os.environ["PYSPARK_PYTHON"]
              |driverpy = os.environ["PYSPARK_DRIVER_PYTHON"]
              |""".stripMargin){
            case (vars, output) =>
              expectations.foreach {
                case (k, v) =>
                  vars(k) shouldEqual v
              }
          }
          val cleanupTasks = Seq(
            () => Main.conf.remove("spark.pyspark.driver.python"),
            () => Main.conf.remove("spark.pyspark.python"),
            () => sys.props.remove("PYSPARK_DRIVER_PYTHON"),
            () => sys.props.remove("PYSPARK_PYTHON"))

          tasks.foreach(i => cleanupTasks(i)())
        }
        "one" in {
          precedenceTest(0 to 3, Map(
            "driverpy" -> "one",
            "py" -> "two"))
        }
        "two" in {
          precedenceTest(1 to 3, Map(
            "driverpy" -> "two",
            "py" -> "two"))
        }
        "three" in {
          precedenceTest(2 to 3, Map(
            "driverpy" -> "three",
            "py" -> "four"))
        }
        "four" in {
          precedenceTest(Seq(3), Map(
            "driverpy" -> "four",
            "py" -> "four"))
        }
      }

      "should capture py4j exceptions" in {
        initialize()
        try {
          assertOutput("spark.read.text(\"doesnotexist\")"){ case _ => }
        } catch {
          case err: Throwable =>
            err shouldBe a[RuntimeException]
            err.getMessage should include ("AnalysisException: 'Path does not exist:")
            err.getCause shouldBe a[RuntimeException]
            err.getCause.getMessage should include ("Py4JJavaError: An error occurred while calling")
            err.getCause.getCause shouldBe a[AnalysisException]
            err.getCause.getCause.getMessage should include ("Path does not exist:")
        }
      }
    }
  }
}
