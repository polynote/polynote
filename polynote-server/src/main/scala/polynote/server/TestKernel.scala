//package polynote.server
//
//import cats.effect.{Concurrent, IO}
//import fs2.concurrent.{Queue, Topic}
////
//import scala.reflect.internal.util.Position
//import scala.tools.nsc.{Global, Settings}
//import scala.tools.nsc.reporters.Reporter
//
//// TODO: move into separate project, so it can be 2.11
//class TestKernel {
//
//  private val settings = new Settings()
//  settings.classpath
//
//  object reporter extends Reporter {
//    // TODO: collect reports into a stream?
//    protected def info0(pos: Position, msg: String, severity: Severity, force: Boolean): Unit = ()
//  }
//
//  private val global = new Global(settings)
//  private val interactive = new scala.tools.nsc.interactive.Global(settings, global.reporter)
//
//
//
//}
//
//object TestKernel {
//  final case class Report(position: Position, msg: String, severity: Reporter#Severity, force: Boolean)
//}