package polynote.runtime

import scala.collection.mutable

object Runtime {

  def init(): Unit = ()

  private val externalValues = new mutable.HashMap[String, Any]()

  def getValue(name: String): Any = externalValues(name)
  def putValue(name: String, value: Any): Unit = externalValues.put(name, value)

  object display {
    def html(html: String): Unit = displayer("text/html", html)
    def content(contentType: String, content: String): Unit = displayer(contentType, content)
  }

  def setProgress(progress: Double, detail: String): Unit = progressSetter(progress, detail)

  private var displayer: (String, String) => Unit = (_, _) => println("Display publisher is not correctly configured.")

  def setDisplayer(fn: (String, String) => Unit): Unit = displayer = fn

  private var progressSetter: (Double, String) => Unit = (_, _) => ()

  def setProgressSetter(fn: (Double,String) => Unit): Unit = progressSetter = fn

  def clear(): Unit = externalValues.clear()

}
