package polynote.kernel.util

import cats.effect.{Concurrent, IO}
import polynote.kernel.ResultValue
import polynote.messages.{CellID, Notebook}

import scala.annotation.tailrec

/**
  * Tracks all the CellContexts for a notebook. A good old-fashioned linked list in reverse, with an extra reference to the
  * tip for easy append (i.e. predefs)
  */
class NotebookContext(getNotebook: () => IO[Notebook])(implicit concurrent: Concurrent[IO]) {

  @volatile private var _first: CellContext = _
  @volatile private var _last: CellContext = _

  def init(): IO[Unit] = getNotebook().flatMap {
    notebook => IO {
      synchronized {
        val (first, last) = {
          val notebook = getNotebook().unsafeRunSync()
          if (notebook.cells.nonEmpty) {
            val first = CellContext.unsafe(notebook.cells.head.id, None)
            val last = notebook.cells.tail.foldLeft(first) {
              (prev, next) => CellContext.unsafe(next.id, Some(prev))
            }
            (first, last)
          } else {
            (null, null)
          }
        }
        _first = first
        _last = last
      }
    }
  }

  def insert(cellContext: CellContext, after: Option[CellID]): Unit = after match {
    case None => synchronized {
      if (_first != null) {
        _first.setPrev(cellContext)
      } else if (_last == null) {
        _last = cellContext
      }
    }
    case Some(id) =>
      @tailrec def findTail(at: Option[CellContext], currentTail: List[CellContext]): List[CellContext] = at match {
        case Some(ctx) if ctx.id == id => ctx :: currentTail
        case Some(ctx) => findTail(ctx.previous, ctx :: currentTail)
        case None => Nil
      }

      synchronized {
        findTail(Option(_last), Nil) match {
          case Nil => insert(cellContext, None)
          case at :: tail =>
            val cellsBefore = at.collectBack {
              case ctx => ctx.id
            }.toSet

            cellContext.setPrev(at)

            tail.headOption.foreach(_.setPrev(cellContext))

            tail.filter(_.previous.exists(ctx => cellsBefore(ctx.id))).foreach {
              ctx => ctx.setPrev(cellContext)
            }
        }
      }
  }

  def remove(id: CellID): Unit = synchronized {
    var last = this._last

    if (last != null && last.id == id) {
      if (last.previous.isDefined) {
        this._last = last.previous.get
      } else {
        assert(this._first == last, "Broken links")
        this._last = null
        this._first = null
      }
    } else if (last != null) {
      var nextToLast: CellContext = last
      last = last.previous.orNull

      while (last != null) {
        if (last.id == id) {
          nextToLast.setPrev(last.previous)
          return
        }
        nextToLast = last
        last = last.previous.orNull
      }
      // not found
      throw new NoSuchElementException(s"No cell context with id $id to remove")
    }
  }

  def get(id: CellID): Option[CellContext] = find(_.id == id)
  def getIO(id: CellID): IO[CellContext] = IO(find(_.id == id)).flatMap {
    case None => IO.raiseError(new NoSuchElementException(s"Cell $id"))
    case Some(ctx) => IO.pure(ctx)
  }

  def find(fn: CellContext => Boolean): Option[CellContext] = {
    @tailrec def impl(ctxOpt: Option[CellContext]): Option[CellContext] = ctxOpt match {
      case some@Some(ctx) if fn(ctx) => some
      case Some(ctx) => impl(ctx.previous)
      case None => None
    }
    impl(Option(_last))
  }

  def first: Option[CellContext] = Option(_first)
  def last: Option[CellContext] = Option(_last)

  def allResultValues: List[ResultValue] = Option(_last).toList.flatMap(_.visibleValues)

  /**
    * If the context is already present, replace it with the given context and return true. Otherwise, return false.
    */
  def tryUpdate(context: CellContext): Boolean = find(_.previous.exists(_.id == context.id)) match {
    case Some(successor) =>
      synchronized {
        context.setPrev(successor.previous.flatMap(_.previous))
        successor.setPrev(context)
        true
      }

    case None => false
  }

}
