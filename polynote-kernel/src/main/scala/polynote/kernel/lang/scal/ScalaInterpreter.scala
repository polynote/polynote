package polynote.kernel.lang.scal

import java.io.{File, PrintStream}
import java.util.concurrent.{Executor, Executors, ThreadFactory}

import cats.effect.concurrent.{Deferred, Semaphore}
import cats.effect.internals.IOContextShift
import cats.effect.{ContextShift, IO}
import cats.syntax.all._
import cats.instances.list._
import fs2.{Chunk, Stream}
import fs2.concurrent.{Enqueue, Queue, Topic}
import org.log4s.getLogger
import polynote.kernel.PolyKernel.EnqueueSome
import polynote.kernel._
import polynote.kernel.lang.LanguageInterpreter
import polynote.kernel.util._
import polynote.messages.{CellID, CellResult, ShortList, ShortString, TinyList, TinyString}

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.reflect.runtime
import scala.tools.reflect.ToolBox

class ScalaInterpreter(
  val kernelContext: KernelContext
) extends LanguageInterpreter[IO] {

  import kernelContext.{global, runtimeMirror, runtimeTools, importFromRuntime, importToRuntime}
  private val logger = getLogger

  protected implicit val contextShift: ContextShift[IO] = IO.contextShift(kernelContext.executionContext)

  private val interpreterLock = Semaphore[IO](1).unsafeRunSync()

  protected val shutdownSignal = ReadySignal()

  override def shutdown(): IO[Unit] = shutdownSignal.complete

  protected val previousSources: mutable.HashMap[CellID, ScalaSource[kernelContext.global.type]] = new mutable.HashMap()

  lazy val notebookPackageName = "$notebook"
  lazy val notebookPackageTerm = global.TermName(notebookPackageName)
  def notebookPackage = global.Ident(notebookPackageTerm)
  protected lazy val notebookPackageSymbol = global.internal.newModuleAndClassSymbol(global.rootMirror.RootPackage, notebookPackageTerm)

  // TODO: we want to get rid of predef and load `kernel` from the KernelContext
  def predefCode: Option[String] = Some("val kernel = polynote.runtime.Runtime")

  override def init(): IO[Unit] = IO.unit // pass for now

  // Compile and initialize the module, but don't reflect its values or output anything
  def compileAndInit(source: ScalaSource[kernelContext.global.type]): IO[Unit] = {
    import source.cellContext
    IO.fromEither(source.compile).flatMap {
      case global.NoSymbol => IO.unit
      case sym =>
        val saveSource = IO.delay[Unit](previousSources.put(cellContext.id, source))
        val setModule = cellContext.module.complete(sym.asModule)
        setModule *> IO(kernelContext.runInterruptible(importToRuntime.importSymbol(sym))).map {
          runtimeSym =>
            kernelContext.runInterruptible {
              val moduleMirror = try runtimeMirror.reflectModule(runtimeSym.asModule) catch {
                case err: ExceptionInInitializerError => throw RuntimeError(err.getCause)
                case err: Throwable =>
                  throw RuntimeError(err) // could be linkage errors which won't get handled by IO#handleErrorWith
              }

              val instMirror = try runtimeMirror.reflect(moduleMirror.instance) catch {
                case err: ExceptionInInitializerError => throw RuntimeError(err.getCause)
                case err: Throwable =>
                  throw RuntimeError(err)
              }
            }
        } *> saveSource
    }
  }

  def compileAndRun(source: ScalaSource[kernelContext.global.type]): IO[Stream[IO, Result]] = {
    import source.cellContext, cellContext.id
    val originalOut = System.out
    interpreterLock.acquire.bracket { _ =>
      IO.fromEither(source.compile).flatMap {
        case global.NoSymbol => IO.pure(Stream.empty)
        case sym =>
          val saveSource = IO.delay[Unit](previousSources.put(id, source))
          val setModule = cellContext.module.complete(sym.asModule)
          val symType = global.exitingTyper(sym.asModule.toType)

          setModule *> Queue.unbounded[IO, Option[Result]].flatMap { maybeResultQ =>
            val resultQ = new EnqueueSome(maybeResultQ)
            val run = IO(kernelContext.runInterruptible(importToRuntime.importSymbol(sym))).map {
              runtimeSym =>
                kernelContext.runInterruptible {
                  val moduleMirror = try runtimeMirror.reflectModule(runtimeSym.asModule) catch {
                    case err: ExceptionInInitializerError => throw RuntimeError(err.getCause)
                    case err: Throwable =>
                      throw RuntimeError(err) // could be linkage errors which won't get handled by IO#handleErrorWith
                  }

                  val instMirror = try runtimeMirror.reflect(moduleMirror.instance) catch {
                    case err: ExceptionInInitializerError => throw RuntimeError(err.getCause)
                    case err: Throwable =>
                      throw RuntimeError(err)
                  }

                  val runtimeType = instMirror.symbol.info

                  // collect term definitions and values from the cell's object, and publish them to the symbol table
                  // TODO: We probably also want to publish some output for types, like "Defined class Foo" or "Defined type alias Bar".
                  //       But the class story is still WIP (i.e. we might want to pull them out of cells into the notebook package)
                  symType.nonPrivateDecls.filter(d => d.isTerm && !d.isConstructor && !d.isSetter).collect {

                    case accessor if accessor.isGetter || (accessor.isMethod && !accessor.isOverloaded && accessor.asMethod.paramLists.isEmpty && accessor.isStable) =>
                      // if the decl is a val, evaluate it and push it to the symbol table
                      val name = accessor.decodedName.toString
                      val tpe = global.exitingTyper(accessor.info.resultType)
                      val method = runtimeType.decl(scala.reflect.runtime.universe.TermName(name)).asMethod
                      val owner = method.owner

                      // invoke the accessor for its side effect(s), even if it returns Unit
                      val value = instMirror
                        .reflectMethod(method)
                        .apply()

                      // don't publish if the type is Unit
                      if (accessor.info.finalResultType <:< global.typeOf[Unit])
                        None
                      else
                        Some(ResultValue(kernelContext, accessor.name.toString, tpe, value, id, Some((accessor.pos.start, accessor.pos.end))))

                    case method if method.isMethod && method.originalInfo.typeParams.isEmpty =>
                      // If the decl is a def, we push an anonymous (fully eta-expanded) function value to the symbol table.
                      // The Scala interpreter uses the original method, but other interpreters can use the function.
                      val runtimeMethod = importToRuntime.importSymbol(method.asMethod).asMethod
                      val fnSymbol = if (runtimeMethod.isOverloaded) {
                        resultQ.enqueue1(
                          CompileErrors(
                            KernelReport(
                              new Pos(sym.pos),
                              s"Warning: overloads of method ${method.nameString} may not be available to some kernels",
                              KernelReport.Warning) :: Nil)
                        ).unsafeRunAsyncAndForget()
                        runtimeMethod.alternatives.head.asMethod
                      } else runtimeMethod.asMethod

                      // The function delegates to the method via reflection, which isn't good, but the Scala kernel doesn't
                      // use it anyway, and other interpreters would have to use reflection anyhow
                      try {
                        val (runtimeFn, fnType) = {
                          import runtime.universe._
                          val args = fnSymbol.paramLists.headOption.map(_.map(arg => ValDef(Modifiers(), TermName(arg.name.toString), TypeTree(arg.info), EmptyTree))).toList
                          val fnArgs = args.flatten.map(param => Ident(param.name))

                          // eta-expand if necessary
                          val call = fnSymbol.paramLists.size match {
                            case 0 => q"$fnSymbol"
                            case 1 => q"$fnSymbol(..$fnArgs)"
                            case _ => q"$fnSymbol(..$fnArgs) _"
                          }
                          val tree = Function(args.flatten, call)
                          val typ = runtimeTools.typecheck(tree).tpe
                          runtimeTools.eval(tree) -> typ
                        }
                        val methodType = importFromRuntime.importType(fnType)

                        // I guess we're saying a "def" shouldn't become the cell result?
                        Some(ResultValue(kernelContext, method.name.toString, methodType, runtimeFn, id, Some((method.pos.start, method.pos.end))))
                      } catch {
                        case err: Throwable => Some(CompileErrors(List(KernelReport(
                          Pos(source.cellName, method.pos.start, method.pos.end, method.pos.start),
                          s"Unable to create eta-expanded method for ${method.name}; it may not be available to other languages",
                          KernelReport.Warning))))
                      }

                  }.flatten
                }
            }.flatMap(results => saveSource.as(results)).uncancelable

            val eval = Queue.unbounded[IO, Option[Chunk[Byte]]].map(new QueueOutputStream(_)).flatMap {
              stdOut =>
                val outputs = stdOut.queue.dequeue
                  .unNoneTerminate
                  .flatMap(Stream.chunk)
                  .through(fs2.text.utf8Decode)
                  .map { o =>
                    Output("text/plain; rel=stdout", o)
                  }

                // we need to capture and release *on the thread that is executing the code* because Console.setOut is set **per thread**!
                val runWithCapturedStdout = IO {
                  val newOut = new PrintStream(stdOut)
                  System.setOut(newOut)
                  Console.setOut(newOut)
                }.bracket(_ => run <* IO(stdOut.flush())) {
                  _ => IO {
                    System.setOut(originalOut)
                    Console.setOut(originalOut)
                  }.flatMap(_ => IO(stdOut.close()))
                }

                for {
                  pub   <- outputs.through(resultQ.enqueue).compile.drain.start
                  fiber <- runWithCapturedStdout.start
                } yield fiber.join.uncancelable.handleErrorWith(err => IO.pure(List(ErrorResult(err)))) <* pub.join
            }

            eval.map {
              ioValues =>
                Stream(
                  maybeResultQ.dequeue.unNoneTerminate,
                  Stream.eval(ioValues).flatMap(Stream.emits) ++ Stream.eval(maybeResultQ.enqueue1(None)).drain
                ).parJoinUnbounded
            }
          }
      }
    }(_ => interpreterLock.release)
  }

  override def runCode(
    cellContext: CellContext,
    code: String
  ): IO[Stream[IO, Result]] = {
    val previous = cellContext.collectBack {
      case c if previousSources contains c.id => previousSources(c.id)
    }.reverse

    val source = ScalaSource(kernelContext, cellContext, previous, notebookPackageName, code)
    compileAndRun(source)

  }

  override def completionsAt(
    cellContext: CellContext,
    code: String,
    pos: Int
  ): IO[List[Completion]] = {
    val previous = cellContext.collectBack {
      case c if previousSources contains c.id => previousSources(c.id)
    }.reverse

    IO.fromEither(ScalaSource(kernelContext, cellContext, previous, notebookPackageName, code).completionsAt(pos)).map {
      case (typ, completions) => completions.map { sym =>
        val name = sym.name.decodedName.toString
        val symType = sym.typeSignatureIn(typ)
        val tParams = TinyList(sym.typeParams.map(tp => TinyString(tp.nameString)))
        val params = TinyList {
          for {
            pl <- sym.paramLists
          } yield TinyList {
            for {
              p <- pl
            } yield (TinyString(p.name.decodedName.toString), ShortString(formatType(p.typeSignatureIn(symType))))
          }
        }
        val symTypeStr = if (sym.isMethod) formatType(symType.finalResultType) else ""
        Completion(TinyString(name), tParams, params, ShortString(symTypeStr), completionType(sym))
      }
    }.handleErrorWith(err => IO(logger.error(err)("Completions error")).as(Nil))
  }

  override def parametersAt(
    cellContext: CellContext,
    code: String,
    pos: Int
  ): IO[Option[Signatures]] = {
    val previous = cellContext.collectBack {
      case c if previousSources contains c.id => previousSources(c.id)
    }.reverse

    IO.fromEither(ScalaSource(kernelContext, cellContext, previous, notebookPackageName, code).signatureAt(pos)).map {
      case (typ: global.MethodType, syms, n, d) =>
        val hints = syms.map {
          sym =>

            val paramsStr = sym.paramLists.map {
              pl => "(" + pl.map {
                param => s"${param.name.decodedName.toString}: ${param.typeSignatureIn(typ).finalResultType.toString}"
              }.mkString(", ") + ")"
            }.mkString

            try {
              Some {
                ParameterHints(
                  TinyString(s"${sym.name.decodedName.toString}$paramsStr"),
                  None,
                  TinyList {
                    sym.paramLists.flatMap {
                      pl => pl.map {
                        param => ParameterHint(
                          TinyString(param.name.decodedName.toString),
                          TinyString(param.typeSignatureIn(typ).finalResultType.toString),
                          None  // TODO
                        )
                      }
                    } // TODO: could provide the rest of the param lists?
                  }
                )
              }
            } catch {
              case err: Throwable =>
                err.printStackTrace()
                None
            }
        }
        Option(Signatures(hints.flatMap(_.toList), 0, n.toByte))
      case _ => None
    }.handleErrorWith {
      case NoApplyTree => IO.pure(None)
      case err => IO(logger.error(err)("Completions error")).as(None)
    }
  }



  def formatType(typ: global.Type): String = typ match {
    case mt @ global.MethodType(params, result) =>
      val paramStr = params.map {
        sym => s"${sym.nameString}: ${formatType(sym.typeSignatureIn(mt))}"
      }.mkString(", ")
      val resultType = formatType(result)
      s"($paramStr) => $resultType"

    case _ =>
      val typName = typ.typeSymbol.name
      val typNameStr = typ.typeSymbol.nameString
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

  def completionType(sym: global.Symbol): CompletionType =
    if (sym.isAccessor)
      CompletionType.Field
    else if (sym.isMethod)
      CompletionType.Method
    else if (sym.isPackageObjectOrClass)
      CompletionType.Package
    else if (sym.isTrait)
      CompletionType.TraitType
    else if (sym.isModule)
      CompletionType.Module
    else if (sym.isClass)
      CompletionType.ClassType
    else if (sym.isVariable)
      CompletionType.Term
    else CompletionType.Unknown
}

object ScalaInterpreter {
  class Factory() extends LanguageInterpreter.Factory[IO] {
    override val languageName: String = "Scala"
    override def apply(dependencies: List[(String, File)], kernelContext: KernelContext): LanguageInterpreter[IO] =
      new ScalaInterpreter(kernelContext)
  }

  def factory(): LanguageInterpreter.Factory[IO] = new Factory()
}