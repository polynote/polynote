package polynote.kernel.lang.scal

import scala.reflect.internal.util.{NoSourceFile, SourceFile}
import scala.reflect.io.VirtualFile

final case class CellSourceFile(id: String) extends SourceFile {
  def content: Array[Char] = NoSourceFile.content
  object file extends VirtualFile(id, id)
  def isLineBreak(idx: Int): Boolean = NoSourceFile.isLineBreak(idx)
  def isEndOfLine(idx: Int): Boolean = NoSourceFile.isEndOfLine(idx)
  def isSelfContained: Boolean = NoSourceFile.isSelfContained
  def length: Int = NoSourceFile.length
  def offsetToLine(offset: Int): Int = NoSourceFile.offsetToLine(offset)
  def lineToOffset(index: Int): Int = NoSourceFile.lineToOffset(index)

  override def toString: String = id
}
