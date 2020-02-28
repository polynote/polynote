package polynote

package object runtime {
  // TODO: make this into a case class so we can name the fields!
  type CellRange = (Int, Int) // use this for readability when you have some (start: Int, end: Int) range denoting a selection in a cell.
}
