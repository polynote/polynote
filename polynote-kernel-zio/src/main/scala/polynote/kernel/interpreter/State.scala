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

  def collect[A](pf: PartialFunction[State, A]): List[A] = {
    val buf = new ListBuffer[A]
    var state = this
    while (state != Root) {
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
    var s = this
    while(s != Root && predicate(s)) {
      s = s.prev
    }
    s
  }

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
  def insertOrReplace(state: State): State = if (state.id == id || state.prev.id == id) {
    state
  } else if (this eq Root) {
    state.withPrev(this)
  } else if (state.prev.id == prev.id) {
    withPrev(state)
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

  def id(id: Int, prev: State = Root, values: List[ResultValue] = Nil): State = Id(id.toShort, prev, values)

}
