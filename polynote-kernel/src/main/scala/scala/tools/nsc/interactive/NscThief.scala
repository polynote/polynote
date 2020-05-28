package scala.tools.nsc.interactive

import scala.reflect.internal.util.{Position, SourceFile}

object NscThief {
  def typedTree(global: Global, sourceFile: SourceFile): global.Tree = {
    val prevGlobalPhase = global.globalPhase
    global.globalPhase = global.currentRun.typerPhase
    val result = global.typedTree(sourceFile, forceReload = false)
    global.globalPhase = prevGlobalPhase
    result
  }

  def typedTreeAt(global: Global, pos: Position): global.Tree = {
    import global._
    val prevGlobalPhase = globalPhase
    global.globalPhase = currentRun.typerPhase
    val result = global.typedTreeAt(pos)
    global.globalPhase = prevGlobalPhase
    result
  }
}
