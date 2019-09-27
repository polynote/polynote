package polynote.runtime.python

import jep.JepException
import jep.python.{PyCallable, PyObject}
import polynote.runtime._
import polynote.runtime.python.PythonObject.ReturnTypeFor

import scala.collection.JavaConverters._
import scala.reflect.{ClassTag, classTag}
import scala.language.dynamics

/**
  * Just a bit of Scala sugar over [[PyObject]]
  */
class PythonObject(obj: PyObject, runner: PythonObject.Runner) extends Dynamic {
  import PythonObject.unwrapArg

  private[polynote] def unwrap: PyObject = obj

  protected def callPosArgs(callable: PyCallable, args: Seq[AnyRef]): PythonObject = {
    runner.run {
      callable.callAs(classOf[PyObject], args.map(unwrapArg): _*)
    } match {
      case pc: PyCallable => new PythonFunction(pc, runner)
      case po => new PythonObject(po, runner)
    }
  }

  protected def callKwArgs(callable: PyCallable, args: Seq[(String, Any)]): PythonObject = {
    val kwArgIndex = args.indexWhere(_._1.nonEmpty)
    val (posArgs, kwArgs) = if (kwArgIndex >= 0) {
      args.splitAt(kwArgIndex)
    } else (args, Nil)

    val posArgsArray = posArgs.map(tup => unwrapArg(tup._2.asInstanceOf[AnyRef])).toArray
    val kwArgsMap = kwArgs.toMap.mapValues(_.asInstanceOf[AnyRef]).mapValues(unwrapArg).toSeq.toMap // make sure it's a strict map

    runner.run {
      if (posArgsArray.nonEmpty && kwArgsMap.nonEmpty) {
        callable.callAs(classOf[PyObject], posArgsArray, kwArgsMap.asJava)
      } else if (posArgsArray.nonEmpty) {
        callable.callAs(classOf[PyObject], posArgsArray: _*)
      } else if (kwArgsMap.nonEmpty) {
        callable.callAs(classOf[PyObject], kwArgsMap.asJava)
      } else {
        callable.callAs(classOf[PyObject])
      }
    } match {
      case pc: PyCallable => new PythonFunction(pc, runner)
      case po   => new PythonObject(po, runner)
    }
  }

  def hasAttribute(name: String): Boolean = runner.hasAttribute(this, name)
  def asScalaList: List[Any] = runner.asScalaList(this)
  def asScalaMap: Map[Any, Any] = runner.asScalaMap(this)
  def asScalaMapOf[K : ClassTag, V : ClassTag]: Map[K, V] = runner.asScalaMapOf[K, V](this)

  def selectDynamic[T](name: String)(implicit returnType: ReturnTypeFor[T]): returnType.Out = {
    runner.run {
      returnType.wrap(obj.getAttr(name, returnType.tag.runtimeClass.asInstanceOf[Class[returnType.Class]]), runner)
    }
  }

  def updateDynamic(name: String)(value: Any): Unit = runner.run {
    obj.setAttr(name, value)
  }

  def applyDynamic(method: String)(args: Any*): PythonObject =
      callPosArgs(selectDynamic[PyCallable](method), args.asInstanceOf[Seq[AnyRef]])

  def applyDynamicNamed(method: String)(args: (String, Any)*): PythonObject =
      callKwArgs(selectDynamic[PyCallable](method), args)

  def as[T >: Null : ClassTag]: T = if (obj != null)
    obj.as(classTag[T].runtimeClass.asInstanceOf[Class[T]])
  else
    null

  override def toString: String = runner.run {
    try {
      obj.toString
    } catch {
      case err: NullPointerException => "null"
      case err: JepException => "<error converting python object to string>"
    }
  }

  override def hashCode(): Int = runner.run(obj.hashCode())

}


object PythonObject {

  // reprs for untyped python objects – try to get HTML, plaintext, and LaTeX strings from their respective methods
  // and whichever succeeds we'll use.
  implicit object defaultReprs extends ReprsOf[PythonObject] {
    override def apply(obj: PythonObject): Array[ValueRepr] = {
      def attemptRepr(mimeType: String, t: => String): Option[MIMERepr] = try Option(t).map(str => MIMERepr(mimeType, str)) catch {
        case err: Throwable => None
      }

      val htmlRepr = if (obj.hasAttribute("_repr_html_")) attemptRepr("text/html", obj._repr_html_().as[String]) else None
      val textRepr = if (obj.hasAttribute("__repr__")) attemptRepr("text/plain", obj.__repr__().as[String]) else None
      val latexRepr = if (obj.hasAttribute("_repr_latex_")) attemptRepr("application/latex", obj._repr_latex_().as[String]) else None

      List(htmlRepr, textRepr, latexRepr).flatten.toArray
    }
  }

  def unwrapArg(arg: AnyRef): AnyRef = arg match {
    case pyObj: PythonObject => pyObj.unwrap
    case other => other
  }

  // We can't have an overloaded selectDynamic, so this is an approximation of it.
  // When called with no type argument, the type argument is inferred to be Nothing, so we can use this type function
  // to catch that case and return PythonObject.
  trait ReturnTypeFor[T] {
    type Out
    type Class
    def wrap(value: Class, runner: Runner): Out
    def tag: ClassTag[Class]
  }

  object ReturnTypeFor extends ReturnTypeForSomething {
    type Aux[T, Out0] = ReturnTypeFor[T] { type Out = Out0 }

    implicit val forNothing: Aux[Nothing, PythonObject] = new ReturnTypeFor[Nothing] {
      type Out = PythonObject
      type Class = PyObject
      val tag: ClassTag[PyObject] = classTag[PyObject]
      def wrap(value: PyObject, runner: Runner): PythonObject = value match {
        case v: PyCallable => new PythonFunction(v, runner)
        case v => new PythonObject(v, runner)
      }
    }
  }

  private[PythonObject] trait ReturnTypeForSomething { self: ReturnTypeFor.type =>
    implicit def forSomething[T : ClassTag]: Aux[T, T] = new ReturnTypeFor[T] {
      type Out = T
      type Class = T
      val tag: ClassTag[T] = classTag[T]
      def wrap(value: T, runner: Runner): T = value
    }
  }

  trait Runner {
    def run[T](task: => T): T
    def hasAttribute(obj: PythonObject, name: String): Boolean
    def asScalaList(obj: PythonObject): List[Any]
    def asScalaMap(obj: PythonObject): Map[Any, Any]
    def asScalaMapOf[K : ClassTag, V : ClassTag](obj: PythonObject): Map[K, V]
  }

}

/**
  * A [[PythonObject]] which is refined by a constant literal string type indicating its type name. Python objects
  * will be assigned a type like this (i.e. `TypedPythonObject["DataFrame"]`) so that we can use its python type to
  * find specific reprs for that type. For example, we could make an instance of type:
  *
  *     ReprsOf[ TypedPythonObject[Witness.`"DataFrame"`.T] ]
  *
  * which would be selected for a PythonObject["DataFrame"]. Then, this instance could (for example) return a similar
  * streaming data representation to what the Scala DataFrame instance does, enabling the built-in data viz.
  */
class TypedPythonObject[TN <: String](obj: PyObject, runner: PythonObject.Runner) extends PythonObject(obj, runner) {
  final type TypeName = TN
}

// TODO: Implement specific reprs for pandas, numpy, etc
object TypedPythonObject extends AnyPythonReprs

private[runtime] trait AnyPythonReprs { self: TypedPythonObject.type =>

  // default reprs for any typed python object
  implicit def anyReprs[T <: String]: ReprsOf[TypedPythonObject[T]] =
    PythonObject.defaultReprs.asInstanceOf[ReprsOf[TypedPythonObject[T]]]

}