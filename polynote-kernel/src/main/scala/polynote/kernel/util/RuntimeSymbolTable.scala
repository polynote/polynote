package polynote.kernel.util

import java.util.concurrent.ConcurrentHashMap

import cats.effect.{ContextShift, IO}
import fs2.Stream
import fs2.concurrent.{SignallingRef, Topic}
import polynote.kernel.{KernelStatusUpdate, ResultValue}
import polynote.kernel.lang.LanguageInterpreter
import polynote.messages.CellID
import polynote.runtime.{StringRepr, ValueRepr}

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.tools.nsc.interactive.Global

/*final class RuntimeSymbolTable(
  val kernelContext: KernelContext,
  statusUpdates: Publish[IO, KernelStatusUpdate])(implicit
  contextShift: ContextShift[IO]
) extends Serializable {

  import kernelContext.global
  import global.{Type, TermName, Symbol}

  private val currentSymbolTable: ConcurrentHashMap[TermName, RuntimeValue] = new ConcurrentHashMap()
  private val disposed = ReadySignal()

  private val cellIds: mutable.TreeSet[CellID] = new mutable.TreeSet()

  private val newSymbols: Topic[IO, RuntimeValue] =
    Topic[IO, RuntimeValue]{
      // note: IntelliJ doesn't like this but it compiles fine...
      val kernel = RuntimeValue(
        "kernel",
        polynote.runtime.Runtime,
        List(StringRepr(polynote.runtime.Runtime.getClass.getSimpleName)),
        global.typeOf[polynote.runtime.Runtime.type],
        None,
        -1)
      // make sure this is actually in the symbol table.
      // TODO: is there a better way to set this value?
      putValue(kernel)
      kernel
    }.unsafeRunSync()


  private val awaitingDelivery = SignallingRef[IO, Int](0).unsafeRunSync()

  def drain(): IO[Unit] = (Stream.eval(awaitingDelivery.get) ++ awaitingDelivery.discrete).takeWhile(_ > 0).compile.drain

  def currentTerms: Seq[RuntimeValue] = currentSymbolTable.values.asScala.toSeq

  def subscribe(subscriber: Option[Any] = None, maxQueued: Int = 32)(fn: RuntimeValue => IO[Unit]): Stream[IO, (RuntimeValue, Int)] =
    newSymbols.subscribeSize(maxQueued).interruptWhen(disposed()).evalMap {
      case t @ (rv, i) =>
        val res = subscriber match {
          case Some(source) if rv.source.contains(source) => IO.unit // don't send messages back to own source
          case _ => fn(rv)
        }

        res.map { _ =>
          awaitingDelivery.update(_ - 1)
          t
        }
    }

  private def putValue(value: RuntimeValue): Unit = {
    currentSymbolTable.put(global.TermName(value.name), value)
    polynote.runtime.Runtime.putValue(value.name, value.value)

    cellIds.add(value.sourceCellId)
  }

  def publish(source: LanguageInterpreter[IO], sourceCellId: CellID)(name: String, value: Any, staticType: Option[global.Type]): IO[Unit] = {
    val typ = staticType.getOrElse(kernelContext.inferType(value))
    val rv = RuntimeValue(name, value, kernelContext.reprsOf(value, typ), typ, Some(source), sourceCellId)
    for {
      _    <- IO(putValue(rv))
      subs <- newSymbols.subscribers.head.compile.lastOrError
      _    <- IO(awaitingDelivery.update(_ + subs))
      _    <- newSymbols.publish1(rv)
    } yield ()
  }

  def publishAll(values: List[RuntimeValue]): IO[Unit] = {
    IO {
      values.foreach {
        rv =>
          putValue(rv)
      }
    }.flatMap {
      _ => for {
        subs <- newSymbols.subscribers.head.compile.lastOrError
        _    <- IO(awaitingDelivery.update(_ + (subs * values.length)))
        _    <- Stream.emits(values).to(newSymbols.publish).compile.drain
      } yield ()
    }
  }

  def close(): Unit = disposed.completeSync()

  def formatType(typ: global.Type): String = kernelContext.formatType(typ)

  sealed case class RuntimeValue(
    name: String,
    value: Any,
    reprs: List[ValueRepr],
    scalaTypeHolder: global.Type,
    source: Option[LanguageInterpreter[IO]],
    sourceCellId: CellID
  ) extends SymbolDecl[IO] {

    override def scalaType(g: Global): g.Type = if (g eq global) {
      scalaTypeHolder.asInstanceOf[g.Type] // this is safe because we have established that the globals are the same
    } else throw new Exception("should never happen")

    override def getValue: Option[Any] = Option(value)

    // don't need to hash everything to determine hash code; name collisions are less common than hash comparisons
    override def hashCode(): Int = name.hashCode()

    def toResultValue: ResultValue = ResultValue(kernelContext, name, reprs, scalaTypeHolder, value, sourceCellId)
  }

  object RuntimeValue {
    def apply(name: String, value: Any, source: Option[LanguageInterpreter[IO]], sourceCell: CellID): RuntimeValue = {
      val typ = kernelContext.inferType(value)
      RuntimeValue(name, value, kernelContext.reprsOf(value, typ), typ, source, sourceCell)
    }

    def fromSymbolDecl(symbolDecl: SymbolDecl[IO]): Option[RuntimeValue] = {
      symbolDecl.getValue.map { value =>
        apply(symbolDecl.name, value, symbolDecl.source, symbolDecl.sourceCellId)
      }
    }

    def fromResultValue(resultValue: ResultValue, source: LanguageInterpreter[IO]): Option[RuntimeValue] = resultValue match {
      case ResultValue(_, _, _, _, Unit, _) => None
      case ResultValue(name, typeName, reprs, sourceCell, value, scalaType) =>
        Some(apply(name, value, reprs, scalaType.asInstanceOf[global.Type], Option(source), sourceCell))
    }
  }

}

/**
  * A symbol defined in a notebook cell
  */
trait SymbolDecl[F[_]] {
  def name: String
  def source: Option[LanguageInterpreter[F]]
  def sourceCellId: CellID
  def scalaType(g: Global): g.Type
  def getValue: Option[Any]
}*/
