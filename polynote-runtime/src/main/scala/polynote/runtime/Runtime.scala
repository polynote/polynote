package polynote.runtime

import java.util.concurrent.ConcurrentHashMap

import polynote.buildinfo.BuildInfo

// TODO: can we make this a class instantiated per-kernel? Currently we rely on the ClassLoader to prevent cross-contamination
object Runtime extends Serializable {

  def init(): Unit = ()

  @transient private val externalValues = new ConcurrentHashMap[String, Any]()

  def getValue(name: String): Any = externalValues.get(name)
  def putValue(name: String, value: Any): Unit =
    if (value != null)
      externalValues.put(name, value)
    else
      externalValues.remove(name)

  object display {
    def html(html: String): Unit = displayer("text/html", html)
    def content(contentType: String, content: String): Unit = displayer(contentType, content)
  }

  def setProgress(progress: Double, detail: String): Unit = progressSetter(progress, detail)

  def setExecutionStatus(startPos: Int, endPos: Int): Unit = executionStatusSetter(Some((startPos, endPos)))
  def clearExecutionStatus(): Unit = executionStatusSetter(None)

  @transient private var displayer: (String, String) => Unit = (_, _) => println("Display publisher is not correctly configured.")

  def setDisplayer(fn: (String, String) => Unit): Unit = displayer = fn

  @transient private var progressSetter: (Double, String) => Unit = (_, _) => ()

  def setProgressSetter(fn: (Double,String) => Unit): Unit = progressSetter = fn

  @transient private var executionStatusSetter: Option[(Int, Int)] => Unit = (_ => ())

  def setExecutionStatusSetter(fn: Option[(Int, Int)] => Unit): Unit = executionStatusSetter = fn

  def clear(): Unit = externalValues.clear()

  def currentRuntime: Runtime.type = this

  def version: String = BuildInfo.version
  def commit: String = BuildInfo.commit
}
