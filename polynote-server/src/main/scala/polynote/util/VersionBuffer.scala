package polynote.util

import scala.collection.mutable.{ArrayBuffer, ListBuffer}

class VersionBuffer[T] {

  private val buffer = new ArrayBuffer[(Int, T)]

  protected final def versionIndex(version: Int): Int = buffer.indexWhere(_._1 == version)
  protected final def versionedValueAt(index: Int): (Int, T) = buffer(index)
  protected final def setValueAt(index: Int, value: T): Unit = buffer(index) = buffer(index).copy(_2 = value)
  protected final def numVersions: Int = buffer.size

  def add(version: Int, value: T): Unit = synchronized {
    if (buffer.isEmpty) {
      buffer += ((version, value))
    } else {
      buffer.last match {
        case (ver, _) =>
          require(ver < version || version == 0, "Cannot add version older than newest version")
          buffer += ((version, value))
      }
    }
  }

  def updateRange(values: Seq[(Int, T)]): Unit = synchronized {
    values.foreach {
      case v@(ver, t) =>
        val index = buffer.indexWhere(_._1 == ver)
        if (index >= 0) {
          buffer.update(index, v)
        }
    }
  }

  def oldestVersion: Option[Int] = buffer.headOption.map(_._1)
  def newestVersion: Option[Int] = buffer.lastOption.map(_._1)

  def discardUntil(version: Int): Unit = synchronized {
    val idx = buffer.indexWhere(_._1 == version)
    if (idx > 0) {
      buffer.remove(0, idx)
    }
  }

  def getRange(startVersion: Int, endVersion: Int): List[T] = getRangeV(startVersion, endVersion).map(_._2)

  def getRangeV(startVersion: Int, endVersion: Int): List[(Int, T)] = {
    val iter = buffer.iterator
    val results = new ListBuffer[(Int, T)]

    if (!iter.hasNext)
      return Nil

    var current = iter.next()
    while (current._1 < startVersion && iter.hasNext)
      current = iter.next()

    if (current._1 < startVersion)
      return Nil

    results += current

    // wrapped around
    if (startVersion > endVersion) {
      while (current._1 >= startVersion && iter.hasNext) {
        current = iter.next()
        results += current
      }
    }

    while (current._1 < endVersion && iter.hasNext) {
      current = iter.next()
      results += current
    }

    results.toList
  }

}
