package polynote.kernel.interpreter

import polynote.kernel.ResultValue
import polynote.messages.CellID

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
  * A state of cell execution.
  */
trait State {
  import State.Root

  /**
    * The cell ID
    */
  def id: CellID

  /**
    * The state on which this one depends
    */
  def prev: State

  /**
    * The values contained by this state (i.e. defined by the cell whose execution state this represents)
    */
  def values: List[ResultValue]

  // TODO: make this protected, public API should prevent you from removing Root from the chain
  def withPrev(prev: State): State
  def updateValues(fn: ResultValue => ResultValue): State

  /**
    * Perform the side effect for each state, moving back through states in reverse chronological order
    */
  def foreachPrev(fn: State => Unit): Unit = {
    var state = this
    while (!(state eq Root)) {
      fn(state)
      state = state.prev
    }
  }

  /**
    * @return a list of states in reverse chronological order
    */
  def toList: List[State] = collect { case s => s }

  /**
    * @return a list of states in reverse chronological order, until (but not including) the predicate fails to hold
    */
  def takeWhile(fn: State => Boolean): List[State] = {
    var state = this
    val result = new ListBuffer[State]
    while (!(state eq Root) && fn(state)) {
      result += state
      state = state.prev
    }
    result.toList
  }

  /**
    * @return a list of states in reverse chronological order, until the predicate is satisfied, *including* the state
    *         which satisfied the predicate (if any)
    */
  def takeUntil(fn: State => Boolean): List[State] = {
    var state = this
    val result = ListBuffer(state)
    while (!(state eq Root) && !fn(state)) {
      state = state.prev
      result += state
    }
    result.toList
  }

  /**
    * Evaluate the given partial function in all states where it's defined, travelling in reverse chronological order
    * and collecting results into a List.
    */
  def collect[A](pf: PartialFunction[State, A]): List[A] = {
    val buf = new ListBuffer[A]
    var state = this
    while (!(state eq Root)) {
      if (pf.isDefinedAt(state)) {
        buf += pf(state)
      }
      state = state.prev
    }
    buf.toList
  }

  def scope: List[ResultValue] = {
    val visible = new mutable.ListMap[String, ResultValue]
    var state = this
    while (state != Root) {
      state.values.foreach {
        rv => if (!visible.contains(rv.name)) {
          visible.put(rv.name, rv)
        }
      }
      state = state.prev
    }
    visible.values.toList
  }

  /**
    * Find the furthest state in the chain where the predicate still holds
    */
  def rewindWhile(predicate: State => Boolean): State = {
    if (!predicate(this)) {
      return this
    }
    var s = this
    while (s != Root && predicate(s.prev)) {
      s = s.prev
    }
    s
  }

  /**
    * Find the first state in the chain where the predicate holds
    */
  def rewindUntil(predicate: State => Boolean): State = {
    if (predicate(this)) {
      return this
    }
    var s = this
    while (s != Root && !predicate(s.prev)) {
      s = s.prev
    }
    s
  }

  def lastPredef: State = rewindUntil(_.id < 0)

  /**
    * Insert the given state after the given state, such that the state with the given ID becomes the given state's
    * predecessor. If no state exists with the given ID, it will be inserted after Root.
    */
  def insert(after: CellID, state: State): State = if (after == id || this == Root) {
    state.withPrev(this)
  } else withPrev(prev.insert(after, state))

  /**
    * Replace the state with the same ID as the given state with the given state
    */
  def replace(state: State): State = if (state.id == id) {
    state.withPrev(prev)
  } else if (this != Root) {
    withPrev(prev.replace(state))
  } else this

  /**
    * Replace the state with the same ID as the given state with the given state. If a state with the given state's
    * predecessor is found before a state with the same ID as the given state, the given state will be inserted
    * between the predecessor and its successor.
    */
  def insertOrReplace(state: State): State = if (state.id == id) {
    state
  } else if (this eq Root) {
    state.withPrev(this)
  } else if (state.prev.id == prev.id) {
    withPrev(state)
  } else if (state.prev.id == id) {
    state
  } else withPrev(prev.insertOrReplace(state))

  def at(id: CellID): Option[State] = {
    var result: Option[State] = None
    var s = this
    while ((result eq None) && s != Root) {
      if (s.id == id) {
        result = Some(s)
      }
      s = s.prev
    }
    result
  }
}

object State {

  case object Root extends State {
    override val id: CellID = Short.MinValue
    override val prev: State = this
    override val values: List[ResultValue] = Nil
    override def withPrev(prev: State): Root.type = this
    override def updateValues(fn: ResultValue => ResultValue): State = this
  }

  final case class Id(id: CellID, prev: State, values: List[ResultValue]) extends State {
    override def withPrev(prev: State): State = copy(prev = prev)
    override def updateValues(fn: ResultValue => ResultValue): State = copy(values = values.map(fn))
  }

  val root: State = Root
  def id(id: Int, prev: State = Root, values: List[ResultValue] = Nil): State = Id(id.toShort, prev, values)

  def predef(prev: State, prevPredef: State): State = id(prevPredef.id + 1, prev)

}
