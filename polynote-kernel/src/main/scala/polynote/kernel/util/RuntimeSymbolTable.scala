package polynote.kernel.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import cats.effect.concurrent.Deferred
import cats.effect.{ContextShift, IO}
import fs2.Stream
import fs2.concurrent.{SignallingRef, Topic}
import polynote.kernel.{KernelStatusUpdate, SymbolInfo, UpdatedSymbols}
import polynote.kernel.lang.LanguageKernel

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.tools.nsc.interactive.Global

final class RuntimeSymbolTable(
  val global: Global,
  val classLoader: ClassLoader,
  statusUpdates: Topic[IO, KernelStatusUpdate])(implicit
  contextShift: ContextShift[IO]
) extends Serializable {
  import global.{Type, TermName, Symbol}

  private val currentSymbolTable: ConcurrentHashMap[TermName, RuntimeValue] = new ConcurrentHashMap()
  private val disposed = ReadySignal()

  private val runtimeMirror = scala.reflect.runtime.universe.runtimeMirror(classLoader)
  private val importFromRuntime = global.internal.createImporter(scala.reflect.runtime.universe)

  private val cellIds: mutable.TreeSet[String] = new mutable.TreeSet()

  private def typeOf(value: Any, staticType: Option[Type]): Type = staticType.getOrElse {
    try {
      importFromRuntime.importType {
        runtimeMirror.reflect(value).symbol.asType.toType
      }
    } catch {
      case err: Throwable => global.NoType
    }
  }

  private val newSymbols: Topic[IO, RuntimeValue] =
    Topic[IO, RuntimeValue](
      RuntimeValue(TermName("kernel"), polynote.runtime.Runtime, global.typeOf[polynote.runtime.Runtime.type], None, "$Predef")
    ).unsafeRunSync()

  private val awaitingDelivery = SignallingRef[IO, Int](0).unsafeRunSync()

  def drain(): IO[Unit] = (Stream.eval(awaitingDelivery.get) ++ awaitingDelivery.discrete).takeWhile(_ > 0).compile.drain

  def currentTerms: Seq[RuntimeValue] = currentSymbolTable.values.asScala.toSeq

  def subscribe(maxQueued: Int = 32)(fn: RuntimeValue => IO[Unit]): Stream[IO, (RuntimeValue, Int)] =
    newSymbols.subscribeSize(maxQueued).interruptWhen(disposed()).evalMap {
      case t @ (rv, i) => fn(rv).map {
        _ =>
          awaitingDelivery.update(_ - 1)
          t
      }
    }

  private def putValue(value: RuntimeValue): Unit = {
    currentSymbolTable.put(value.name, value)
    polynote.runtime.Runtime.putValue(value.name.toString, value.value)

    cellIds.add(value.sourceCellId)
  }

  def publish(source: LanguageKernel[IO], sourceCellId: String)(name: TermName, value: Any, staticType: Option[global.Type]): IO[Unit] = {
    val rv = RuntimeValue(name, value, typeOf(value, staticType), Some(source), sourceCellId)
    for {
      _    <- IO(putValue(rv))
      subs <- newSymbols.subscribers.get
      _    <- IO(awaitingDelivery.update(_ + subs))
      _    <- newSymbols.publish1(rv)
      _    <- statusUpdates.publish1(UpdatedSymbols(SymbolInfo(name.decodedName.toString, rv.typeString, rv.valueString, Nil) :: Nil, Nil))
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
        subs <- newSymbols.subscribers.get
        _    <- IO(awaitingDelivery.update(_ + (subs * values.length)))
        _    <- Stream.emits(values).to(newSymbols.publish).compile.drain
      } yield ()
    }.flatMap {
      _ => statusUpdates.publish1(UpdatedSymbols(
        values.map(rv => SymbolInfo(rv.name.decodedName.toString, rv.typeString, rv.valueString, Nil)), Nil
      ))
    }
  }

  def close(): Unit = disposed.completeSync()

  def formatType(typ: global.Type): String = typ match {
    case mt @ global.MethodType(params: List[Symbol], result: Type) =>
      val paramStr = params.map {
        sym => s"${sym.nameString}: ${formatType(sym.typeSignatureIn(mt))}"
      }.mkString(", ")
      val resultType = formatType(result)
      s"($paramStr) => $resultType"
    case global.NoType => "<Unknown>"
    case _ =>
      val typName = typ.typeSymbolDirect.name
      val typNameStr = typ.typeSymbolDirect.nameString
      typ.typeArgs.map(formatType) match {
        case Nil => typNameStr
        case a if typNameStr == "<byname>" => s"=> $a"
        case a :: b :: Nil if typName.isOperatorName => s"$a $typNameStr $b"
        case a :: b :: Nil if typ.typeSymbol.owner.nameString == "scala" && (typNameStr == "Function1") =>
          s"$a => $b"
        case args if typ.typeSymbol.owner.nameString == "scala" && (typNameStr startsWith "Function") =>
          s"(${args.dropRight(1).mkString(",")}) => ${args.last}"
        case args => s"$typName[${args.mkString(", ")}]"
      }
  }

  sealed case class RuntimeValue(
    name: TermName,
    value: Any,
    scalaType: global.Type,
    source: Option[LanguageKernel[IO]],
    sourceCellId: String
  ) extends SymbolDecl[IO, global.type] {
    lazy val typeString: String = formatType(scalaType)
    lazy val valueString: String = value.toString match {
      case str if str.length > 64 => str.substring(0, 64)
      case str => str
    }

    // don't need to hash everything to determine hash code; name collisions are less common than hash comparisons
    override def hashCode(): Int = name.hashCode()
  }

  object RuntimeValue {
    def apply(name: String, value: Any, source: Option[LanguageKernel[IO]], sourceCell: String): RuntimeValue = RuntimeValue(
      global.TermName(name), value, typeOf(value, None), source, sourceCell
    )
  }
}

/**
  * A symbol defined in a notebook cell
  */
trait SymbolDecl[F[_], G <: Global] {
  def name: G#TermName
  def source: Option[LanguageKernel[F]]
  def sourceCellId: String
  def scalaType: G#Type
}
