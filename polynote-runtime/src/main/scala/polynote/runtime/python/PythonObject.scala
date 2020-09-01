package polynote.runtime.python

import java.nio.ByteBuffer

import jep.{Jep, JepException}
import jep.python.{PyCallable, PyObject}
import polynote.runtime._
import polynote.runtime.python.PythonObject.ReturnTypeFor
import shapeless.Witness

import scala.collection.JavaConverters._
import scala.collection.immutable.ListMap
import scala.reflect.{ClassTag, classTag}
import scala.language.dynamics

/**
  * Just a bit of Scala sugar over [[PyObject]]
  */
class PythonObject(obj: PyObject, private[polynote] val runner: PythonObject.Runner) extends Dynamic {
  import PythonObject.unwrapArg

  private[polynote] def unwrap: PyObject = obj

  protected def callPosArgs(callable: PyCallable, args: Seq[AnyRef]): PythonObject = {
    runner.run {
      if (args.nonEmpty) {
        callable.callAs(classOf[PyObject], args.map(unwrapArg): _*)
      } else {
        callable.callAs(classOf[PyObject])
      }
    } match {
      case pc: PyCallable => new PythonFunction(pc, runner)
      case po if runner.isCallable(po) => new PythonFunction(po.as(classOf[PyCallable]), runner)
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
      case po if runner.isCallable(po) => new PythonFunction(po.as(classOf[PyCallable]), runner)
      case po   => new PythonObject(po, runner)
    }
  }

  def hasAttribute(name: String): Boolean = runner.hasAttribute(this, name)
  def asScalaList: List[PythonObject] = runner.asScalaList(this)
  def asScalaMap: Map[Any, Any] = runner.asScalaMap(this)
  def asScalaMapOf[K : ClassTag, V : ClassTag]: Map[K, V] = runner.asScalaMapOf[K, V](this)
  def asTuple2: (PythonObject, PythonObject) = runner.asTuple2(this)
  def asTuple2Of[A : ClassTag, B : ClassTag]: (A, B) = runner.asTuple2Of[A, B](this)

  def selectDynamic[T](name: String)(implicit returnType: ReturnTypeFor[T]): returnType.Out = {
    runner.run {
      returnType.wrap(obj.getAttr(name, returnType.tag.runtimeClass.asInstanceOf[Class[returnType.Class]]), runner)
    }
  }

  def updateDynamic(name: String)(value: Any): Unit = runner.run {
    obj.setAttr(name, unwrapArg(value.asInstanceOf[AnyRef]))
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

  override def equals(other: Any): Boolean = other match {
    case wrapped: PythonObject =>
      runner.run(obj.equals(wrapped.unwrap))
    case _: PyObject =>
      runner.run(obj.equals(other))
    case _ => false
  }
}


object PythonObject {

  // reprs for untyped python objects – try to get HTML, plaintext, and LaTeX strings from their respective methods
  // and whichever succeeds we'll use.
  implicit object defaultReprs extends ReprsOf[PythonObject] {
    override def apply(obj: PythonObject): Array[ValueRepr] = {
      def attemptRepr(mimeType: String, prepareOpt: Option[String => String], t: => PythonObject): Option[MIMERepr] = {
        val prepare = prepareOpt.getOrElse(identity[String] _)
        try Option(t.as[String]).map(str => MIMERepr(mimeType, prepare(str))) catch {
          case err: Throwable =>
            // According to https://ipython.readthedocs.io/en/stable/config/integrating.html#rich-display it's possible
            // that _repr_*_ functions return tuple (data, metadata) values. For now, we just drop the metadata values
            // In future, we might be interested in doing something with them.
            try Option(t.asTuple2Of[String, Any]).map(tup => MIMERepr(mimeType, prepare(tup._1))) catch {
              case e: Throwable => None
            }
        }
      }

      // For now, don't bother with classes (in future we might want to do something special?)
      // TODO: must be a better way to tell if an object is a class...
      if (obj.hasAttribute("__dict__") && obj.__dict__.hasAttribute("__module__")) {
        Array.empty
      } else {

        val basicMimeReprs = Map(
          "text/html"           -> ("_repr_html_" -> None),
          "text/plain"          -> ("__repr__" -> None),
          "application/x-latex" -> ("_repr_latex_" -> Some((str: String) => str.stripPrefix("$").stripSuffix("$"))),
          "image/svg+xml"       -> ("_repr_svg_" -> None),
          "image/jpeg"          -> ("_repr_jpeg_" -> None),
          "image/png"           -> ("_repr_png_" -> None)
        ).flatMap {
          case (mime, (funcName, prepare)) =>
            if (obj.hasAttribute(funcName)) attemptRepr(mime, prepare, obj.applyDynamic(funcName)()) else None
        }

        val mimeBundleReprs = if (obj.hasAttribute("_repr_mimebundle_")) {
          try {
            obj._repr_mimebundle_().asScalaMapOf[String, String].map { case (k, v) => MIMERepr(k, v) }.toList
          } catch {
            case err: Throwable =>
              // Similarly, _repr_mimebundle_ may also return a tuple of (data, metadata), so we'll do the same as above.
              try {
                obj._repr_mimebundle_().asTuple2._1.asScalaMapOf[String, String].map { case (k, v) => MIMERepr(k, v) }.toList
              } catch {
                case err: Throwable => List.empty[MIMERepr]
              }
          }
        } else List.empty[MIMERepr]

        (basicMimeReprs ++ mimeBundleReprs).toArray
      }
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
        case v if runner.isCallable(v) => new PythonFunction(v.as(classOf[PyCallable]), runner)
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
    def runJep[T](task: Jep => T): T
    def hasAttribute(obj: PythonObject, name: String): Boolean
    def asScalaList(obj: PythonObject): List[PythonObject]
    def asScalaMap(obj: PythonObject): Map[Any, Any]
    def asScalaMapOf[K : ClassTag, V : ClassTag](obj: PythonObject): Map[K, V]
    def asTuple2(obj: PythonObject): (PythonObject, PythonObject)
    def asTuple2Of[A : ClassTag, B : ClassTag](obj: PythonObject): (A, B)
    def typeName(obj: PythonObject): String
    def qualifiedTypeName(obj: PythonObject): String
    def isCallable(obj: PyObject): Boolean
    def len(obj: PythonObject): Int
    def len64(obj: PythonObject): Long
    def list(obj: AnyRef): PythonObject
    def listOf(objs: AnyRef*): PythonObject
    def tupleOf(objs: AnyRef*): PythonObject
    def dictOf(kvs: (AnyRef, AnyRef)*): PythonObject
    def str(obj: AnyRef): PythonObject
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
object TypedPythonObject extends PandasReprs {

}


private[runtime] trait PandasReprs extends AnyPythonReprs { self: TypedPythonObject.type =>
  implicit val dataFrameReprs: ReprsOf[TypedPythonObject[Witness.`"DataFrame"`.T]] = new ReprsOf[TypedPythonObject[Witness.`"DataFrame"`.T]] {
    override def apply(value: TypedPythonObject[Witness.`"DataFrame"`.T]): Array[ValueRepr] = {
      // this should be a Pandas DataFrame, as a PySpark DataFrame gets converted to a Scala value and shouldn't be wrapped in Python object.
      StreamingDataRepr.fromHandle(new pandas.PandasHandle(_, value)) +: PythonObject.defaultReprs(value)
    }
  }
}

private[runtime] trait AnyPythonReprs { self: TypedPythonObject.type =>

  // default reprs for any typed python object
  implicit def anyReprs[T <: String]: ReprsOf[TypedPythonObject[T]] =
    PythonObject.defaultReprs.asInstanceOf[ReprsOf[TypedPythonObject[T]]]

}