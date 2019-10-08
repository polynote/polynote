package polynote.util

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.function.Predicate

import scala.collection.mutable.ListBuffer

class VersionBuffer[T] {

  private val buffer = new ConcurrentLinkedDeque[(Int, T)]()

  def add(version: Int, value: T): Unit = synchronized {
    if (buffer.isEmpty) {
      buffer.addLast((version, value))
    } else {
      buffer.getLast match {
        case (ver, _) =>
          require(ver < version || version == 0, "Cannot add version older than newest version")
          buffer.addLast((version, value))
      }
    }
  }

  def oldestVersion: Option[Int] = Option(buffer.getFirst).map(_._1)
  def newestVersion: Option[Int] = Option(buffer.getLast).map(_._1)

  def discardUntil(version: Int): Unit = synchronized {
    buffer.removeIf {
      new Predicate[(Int, T)] {
        def test(t: (Int, T)): Boolean = t._1 < version
      }
    }
  }

  def getRange(startVersion: Int, endVersion: Int): List[T] = getRangeV(startVersion, endVersion).map(_._2)

  def getRangeV(startVersion: Int, endVersion: Int): List[(Int, T)] = {
    val iter = buffer.iterator()
    val results = new ListBuffer[(Int, T)]
    var finished = false

    if (startVersion > endVersion) {
      // there's a wraparound between start and end
      var lastVersion = 0
      while (!finished && iter.hasNext) {
        val (version, value) = iter.next()
        if (version < lastVersion)
          finished = true
        results += (version -> value)
      }
      finished = false
    }

    while (!finished && iter.hasNext) {
      val (version, value) = iter.next()
      if (version > endVersion) {
        finished = true
      } else {
        results += (version -> value)
      }
    }
    results.toList
  }

}
