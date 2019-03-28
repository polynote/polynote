package polynote.runtime.python

import java.util.concurrent.{Callable, ExecutorService}

import jep.python.{PyCallable, PyObject}
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

  protected def callPosArgs(callable: PyCallable, args: Seq[AnyRef]): AnyRef = {
    runner.run {
      callable.call(args.map(unwrapArg): _*)
    } match {
      case pc: PyCallable => new PythonFunction(pc, runner)
      case po: PyObject   => new PythonObject(po, runner)
      case other => other
    }
  }

  protected def callKwArgs(callable: PyCallable, args: Seq[(String, Any)]): AnyRef = {
    val kwArgIndex = args.indexWhere(_._1.nonEmpty)
    val (posArgs, kwArgs) = if (kwArgIndex >= 0) {
      args.splitAt(kwArgIndex)
    } else (args, Nil)

    val posArgsArray = posArgs.map(tup => unwrapArg(tup._2.asInstanceOf[AnyRef])).toArray
    val kwArgsMap = kwArgs.toMap.mapValues(_.asInstanceOf[AnyRef]).mapValues(unwrapArg).toSeq.toMap // make sure it's a strict map

    runner.run {
      if (posArgsArray.nonEmpty && kwArgsMap.nonEmpty) {
        callable.call(posArgsArray, kwArgsMap.asJava)
      } else if (posArgsArray.nonEmpty) {
        callable.call(posArgsArray: _*)
      } else if (kwArgsMap.nonEmpty) {
        callable.call(kwArgsMap.asJava)
      } else {
        callable.call()
      }
    } match {
      case pc: PyCallable => new PythonFunction(pc, runner)
      case po: PyObject   => new PythonObject(po, runner)
      case other => other
    }
  }

  def selectDynamic[T](name: String)(implicit returnType: ReturnTypeFor[T]): returnType.Out = {
    runner.run {
      returnType.wrap(obj.getAttr(name, returnType.tag.runtimeClass.asInstanceOf[Class[returnType.Class]]), runner)
    }
  }

  def updateDynamic(name: String)(value: Any): Unit = runner.run {
    obj.setAttr(name, value)
  }

  // TODO: Jep doesn't give us a way to invoke a PyCallable and get back a PyObject in case the result can't be converted
  //       to a Java representation. So we'll run into the stringification problem here. We should try to fix this upstream
  //       in Jep.
  def applyDynamic(method: String)(args: Any*): Any =
      callPosArgs(selectDynamic[PyCallable](method), args.asInstanceOf[Seq[AnyRef]])


  def applyDynamicNamed(method: String)(args: (String, Any)*): Any =
      callKwArgs(selectDynamic[PyCallable](method), args)

  override def toString: String = runner.run {
    obj.toString
  }

}

object PythonObject {

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

  object ReturnTypeFor extends ReturnTypeForNothing {
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

  private[PythonObject] trait ReturnTypeForNothing { self: ReturnTypeFor.type =>
    implicit def forSomething[T : ClassTag]: Aux[T, T] = new ReturnTypeFor[T] {
      type Out = T
      type Class = T
      val tag: ClassTag[T] = classTag[T]
      def wrap(value: T, runner: Runner): T = value
    }
  }

  trait Runner {
    def run[T](task: => T): T
  }

}