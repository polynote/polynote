package polynote.kernel.context

import java.util.concurrent.ConcurrentHashMap

import scala.collection.immutable.Queue
import scala.collection.{TraversableLike, immutable, mutable}
import scala.reflect.ClassTag
import scala.tools.nsc.interactive.Global

/**
  * RuntimeContext keeps track of the results of running each cell, and enforces reproducibility by specifying the
  * 'state of the world' for each cell of a notebook based on its position in the notebook relative to its run order.
  *
  * When each cell is run it is provided with a RuntimeContextView of the proper state in that location, where the proper
  * state in this case refers to the order of the cells in the notebook.
  *
  * For example, consider a notebook with the following cells:
  *
  *   cell1:
  *     x = 1
  *     y = 2
  *
  *   cell2:
  *     x = 2
  *
  *   cell3:
  *     z = y
  *
  *  When we run all the cells (*regardless of which order we ran them*) we want to get a structure that looks like (
  *  simplifying because we keep a little more info):
  *
  *   [
  *     (cell1, (x -> 1, y -> 2)),
  *     (cell2, (x -> 2),
  *     (cell3, (z -> 2)
  *   ]
  *
  *   The order is important, because it determines a relationship between the cells that ensures that the kernel should
  *   always work as if all cells were run in order, even though users may run cells out-of-order.
  *
  *   Imagine that now a user adds a new cell in between cell1 and cell2:
  *
  *   cell1:
  *     x = 1
  *     y = 2
  *
  *   cell4:
  *     println(z)
  *
  *   cell2:
  *     x = 2
  *
  *   cell3:
  *     z = y
  *
  *   Running cell4 should *fail*, saying that `z` is an unknown symbol (even though the same situation in Jupyter or
  *   Zeppelin would work), because the RuntimeContextView for cell4 would just be [(cell1, (cell1, (x -> 1, y -> 2)))].
  *   This ensures that notebooks will always work properly when shared with others.
  */

  case class RuntimeContext[G <: GlobalInfo](
    cellId: String,
    globalInfo: G,
    previousCell: Option[RuntimeContext[G]],
    symbols: Map[String, G#RuntimeValue],
    interpreterContext: Option[InterpreterContext],
    maybeResult: Option[G#RuntimeValue]
  ) {
    def visibleSymbols: Seq[G#RuntimeValue] = collect(_.symbols.values).toSeq.distinct :+ out // make sure to add the output map

    def availableContext[T <: InterpreterContext: ClassTag]: Seq[T] = collect[Option[T]] { entry =>
      Seq(entry.interpreterContext.collect {
        case ctx: T => ctx
      })
    }.toSeq.flatten.distinct

    // TODO: is this even useful on its own or should we fold it into `out`
    def resultMap: Map[String, G#RuntimeValue] = collect { entry =>
      entry.maybeResult.map(r => entry.cellId -> r)
    }.toMap

    // TODO: this will be accessible as Out[cell1], do we want it to be Out[1] instead (like iPython)
    def out: globalInfo.RuntimeValue = globalInfo.RuntimeValue("Out", resultMap.mapValues(_.value), None, "")

    def collect[T](f: RuntimeContext[G] => Iterable[T]): Iterable[T] = previousCell match {
      case Some(prev) => prev.collect(f) ++ f(this)
      case None => f(this)
    }
  }

object RuntimeContext {
  def getPredefContext[G <: GlobalInfo](globalInfo: G): RuntimeContext[G] = {
    import globalInfo.global

    val symbols = Map("kernel" -> globalInfo.RuntimeValue(
      global.TermName("kernel"),
      polynote.runtime.Runtime,
      global.typeOf[polynote.runtime.Runtime.type],
      None,
      "$Predef")
    )
    RuntimeContext[G]("$Predef", globalInfo, None, symbols, None, None)
  }
}

// interpreter-specific contextual info
trait InterpreterContext
