package polynote.kernel.util

import cats.effect.{Concurrent, IO}
import polynote.kernel.ResultValue
import polynote.messages.{CellID, Notebook}

import scala.annotation.tailrec

/**
  * Tracks all the CellContexts for a notebook. A good old-fashioned linked list in reverse, with an extra reference to the
  * tip for easy append (i.e. predefs)
  */
class NotebookContext(implicit concurrent: Concurrent[IO]) {

  @volatile private var _first: CellContext = _
  @volatile private var _last: CellContext = _

  def insert(cellContext: CellContext, after: Option[CellID]): CellContext = after match {
    case None => insertFirst(cellContext)
    case Some(id) if id == cellContext.id => throw new IllegalArgumentException("Cell can not be inserted after itself")
    case Some(id) =>
      @tailrec def findTail(at: Option[CellContext], currentTail: List[CellContext]): List[CellContext] = at match {
        case Some(ctx) if ctx.id == id => ctx :: currentTail
        case Some(ctx) => findTail(ctx.previous, ctx :: currentTail)
        case None => Nil
      }

      synchronized {
        findTail(Option(_last), Nil) match {
          case Nil => throw new NoSuchElementException(s"Predecessor $id not found")
          case at :: tail =>
            val cellsBefore = at.collectBack {
              case ctx => ctx.id
            }.toSet

            cellContext.setPrev(at)

            tail.headOption.foreach(_.setPrev(cellContext))

            tail.filter(_.previous.exists(ctx => cellsBefore(ctx.id))).foreach {
              ctx => ctx.setPrev(cellContext)
            }

            if (at eq _last) {
              _last = cellContext
            }

            cellContext
        }
      }
  }

  def insertLast(cellContext: CellContext): CellContext = synchronized {
    if (_last != null) {
      cellContext.setPrev(_last)
      _last = cellContext
      cellContext
    } else {
      assert(_first == null, "Broken links")
      _first = cellContext
      _last = cellContext
      cellContext
    }
  }

  def insertFirst(cellContext: CellContext): CellContext = synchronized {
    if (_first != null) {
      _first.setPrev(cellContext)
      _first = cellContext
      cellContext
    } else {
      assert(_last == null, "Broken links")
      _first = cellContext
      _last = cellContext
      cellContext
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
    } else {
      if (last != null) {
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
    * If the context is already present, replace it with the given context and return old context. Otherwise, return None.
    */
  def tryUpdate(context: CellContext): Option[CellContext] = synchronized {
    find(_.previous.exists(_.id == context.id)) match {
      case Some(successor) =>
        val existing = successor.previous.get // we know due to the exists() that it is defined
        context.setPrev(existing.previous)
        successor.setPrev(context)
        Some(existing)


      case None =>
        // if there's only one cell context, it could be the one to update
        if (_first != null && _first.id == context.id) {
          assert(last eq first, "Broken links")
          val existing = _first
          _first = context
          _last = context
          Some(existing)
        } else {
          // it wasn't found
          None
        }
    }
  }
}
