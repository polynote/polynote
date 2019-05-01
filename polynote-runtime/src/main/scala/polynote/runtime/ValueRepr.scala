package polynote.runtime

import java.io.DataOutput
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import polynote.runtime
import sun.misc.Cleaner

/**
  * A plain text representation of a value
  */
final case class StringRepr(string: String) extends ValueRepr

/**
  * A MIME representation of a value. Binary values must be base64-encoded.
  */
final case class MIMERepr(mimeType: String, content: String) extends ValueRepr

/**
  * A binary representation of a value, encoded in Polynote's format (TODO)
  */
final case class DataRepr(dataType: DataType, data: ByteBuffer) extends ValueRepr

/**
  * An identifier for a "lazy" value. It won't be evaluated until the client asks for it by its handle.
  * We use Ints for the handle, because JS needs to store the handle, and it can't store Long. We just can't have
  * more than 2^32^ different handles... I think there would be bigger issues at that point.
  */
final case class LazyDataRepr private[polynote](handle: Int, dataType: DataType) extends ValueRepr {
  def isEvaluated: Option[Boolean] = LazyDataRepr.getHandle(handle).map(_.isEvaluated)

  def release(): Unit = {
    LazyDataRepr.handles.remove(handle)
  }
}

object LazyDataRepr {

  trait Handle {
    def handle: Int
    def dataType: DataType
    def isEvaluated: Boolean
    def data: ByteBuffer
    def release(): Unit = {
      LazyDataRepr.handles.remove(handle)
    }
  }

  private[polynote] class DefaultHandle(val handle: Int, val dataType: DataType, lazyData: => ByteBuffer) extends Handle {
    @volatile private var computedFlag: Boolean = false

    lazy val data: ByteBuffer = {
      computedFlag = true
      lazyData
    }

    def isEvaluated: Boolean = computedFlag
  }


  private val handles: ConcurrentHashMap[Int, Handle] = new ConcurrentHashMap()
  private val nextHandle: AtomicInteger = new AtomicInteger(0)
  private[polynote] def getHandle(handleId: Int): Option[Handle] = Option(handles.get(handleId))
  private[polynote] def releaseHandle(handleId: Int): Unit = getHandle(handleId).foreach(_.release())
  private[polynote] def fromHandle(mkHandle: Int => Handle): LazyDataRepr = {
    val handleId = nextHandle.getAndIncrement()
    val handle = mkHandle(handleId)
    handles.put(handleId, handle)
    val repr = LazyDataRepr(handleId, handle.dataType)

    // if the repr object gets GC'ed, we can let the handle go as well
    Cleaner.create(repr, new Runnable {
      def run(): Unit = handles.remove(handleId)
    })

    repr
  }

  def apply(dataType: DataType, value: => ByteBuffer): LazyDataRepr = fromHandle(new DefaultHandle(_, dataType, value))

}

/**
  * An identifier for an "updating" value. The server may push one or more binary representations of it over time, and
  * when there won't be anymore updates, the server will push a message indicating this.
  */
final case class UpdatingDataRepr private[polynote](handle: Int, dataType: DataType) extends ValueRepr {
  def tryUpdate(data: ByteBuffer): Boolean = UpdatingDataRepr.getHandle(handle) match {
    case Some(h) => h.update(data)
    case None    => false
  }

  def update(data: ByteBuffer): Unit =
    if (!tryUpdate(data))
      throw new IllegalStateException("Attempt to update a data handle which is already closed")

  def release(): Unit = UpdatingDataRepr.getHandle(handle).foreach(_.release())
}

object UpdatingDataRepr {

  private[polynote] class Handle(handle: Int, dataType: DataType) {
    @volatile private var updater: ByteBuffer => Unit = _
    @volatile private var finalizer: () => Unit = _

    @volatile private var lastState: ByteBuffer = _
    @volatile private var releaseFlag: Int = 0

    def setUpdater(updater: ByteBuffer => Unit): this.type = synchronized {
      if (releaseFlag == 0) synchronized {
        if (this.updater == null && lastState != null) {
          this.updater = updater
          updater.apply(lastState)
        } else {
          this.updater = updater
        }
      }
      this
    }

    def setFinalizer(finalizer: () => Unit): this.type = synchronized {
      if (releaseFlag == 1) {
        finalizer.apply()
        releaseFlag = 2
      } else if (releaseFlag == 0) {
        this.finalizer = finalizer
      }
      this
    }

    def lastData: Option[ByteBuffer] = Option(lastState)

    def update(data: ByteBuffer): Boolean = if (releaseFlag == 0) synchronized {
      if (this.updater != null) {
        this.updater.apply(data)
      } else {
        this.lastState = data
      }
      true
    } else false

    def release(): Unit = if (releaseFlag == 0) synchronized {
      handles.remove(handle)
      this.updater = null
      this.lastState = null

      if (this.finalizer != null) {
        this.finalizer.apply()
      } else {
        releaseFlag = 1
      }
    }
  }

  private val handles: ConcurrentHashMap[Int, Handle] = new ConcurrentHashMap()
  private val nextHandle: AtomicInteger = new AtomicInteger(0)
  private[polynote] def getHandle(handleId: Int): Option[Handle] = Option(handles.get(handleId))
  private[polynote] def releaseHandle(handleId: Int): Unit = getHandle(handleId).foreach(_.release())

  def apply(dataType: DataType): UpdatingDataRepr = {
    val handleId = nextHandle.getAndIncrement()
    val handle = new Handle(handleId, dataType)
    handles.put(handleId, handle)

    val repr = UpdatingDataRepr(handleId, dataType)

    Cleaner.create(repr, new Runnable {
      def run(): Unit = {
        val handle = handles.get(handleId)
        if (handle != null) {
          handles.remove(handleId)
          handle.release()
        }
      }
    })

    repr
  }


}

/**
  * An identifier for a "streaming" value. The server will push zero or more binary representations over time, and when
  * there are no more values, the server will push a message indicating this. In contrast to [[UpdatingDataRepr]],
  * each value pushed is considered part of a collection of values.
  *
  * The server will only push values when requested to do so by the client – the client can either pull chunks of values,
  * or it can ask the server to push all values as they become available.
  */
final case class StreamingDataRepr private[polynote](handle: Int, dataType: DataType, knownSize: Option[Int]) extends ValueRepr

object StreamingDataRepr {

  trait Handle {
    def handle: Int
    def dataType: DataType
    def knownSize: Option[Int]

    @volatile private var finalizer: () => Unit = _
    @volatile private var releaseFlag: Int = 0

    def iterator: Iterator[ByteBuffer]

    private[polynote] def setFinalizer(finalizer: () => Unit): Unit = synchronized {
      if (releaseFlag == 1) {
        finalizer.apply()
        releaseFlag = 2
        this.finalizer = null
      } else {
        this.finalizer = finalizer
      }
    }

    def release(): Unit = if (releaseFlag == 0) synchronized {
      if (finalizer != null) {
        finalizer.apply()
        finalizer = null
        releaseFlag = 2
      } else {
        releaseFlag = 1
      }
    }

    def modify(ops: List[TableOp]): Either[Throwable, Int => Handle]
  }

  private[polynote] class DefaultHandle(
    val handle: Int,
    val dataType: DataType,
    val knownSize: Option[Int],
    iter: => Iterator[ByteBuffer]
  ) extends Handle {
    def iterator: Iterator[ByteBuffer] = iter
    def modify(ops: List[TableOp]): Either[Throwable, Int => Handle] = ops match {
      case Nil => Right(new DefaultHandle(_, dataType, knownSize, iter))
      case _   => Left(new UnsupportedOperationException("Table operators are not supported for this data type"))
    }
  }

  private val handles: ConcurrentHashMap[Int, Handle] = new ConcurrentHashMap()
  private val nextHandle: AtomicInteger = new AtomicInteger(0)
  private[polynote] def getHandle(handleId: Int): Option[Handle] = Option(handles.get(handleId))
  private[polynote] def releaseHandle(handleId: Int): Unit = getHandle(handleId).foreach(_.release())

  def apply(dataType: DataType, knownSize: Option[Int], lazyIter: => Iterator[ByteBuffer]): StreamingDataRepr =
    fromHandle(new DefaultHandle(_, dataType, knownSize, lazyIter))

  def apply(dataType: DataType, knownSize: Int, lazyIter: => Iterator[ByteBuffer]): StreamingDataRepr =
    apply(dataType, Some(knownSize), lazyIter)

  def apply(dataType: DataType, lazyIter: => Iterator[ByteBuffer]): StreamingDataRepr =
    apply(dataType, None, lazyIter)

  private[polynote] def fromHandle(mkHandle: Int => Handle): StreamingDataRepr = {
    val handleId = nextHandle.getAndIncrement()
    val handle = mkHandle(handleId)
    handles.put(handleId, handle)

    val repr = StreamingDataRepr(handleId, handle.dataType, handle.knownSize)

    Cleaner.create(repr, new Runnable {
      def run(): Unit = {
        val handle = handles.get(handleId)
        if (handle != null) {
          try handle.release() catch {
            case err: Throwable =>
              System.err.println("Error cleaning streaming handle")
              err.printStackTrace()
          }
          handles.remove(handleId)
        }
      }
    })

    repr
  }

}

sealed trait ValueRepr

sealed trait TableOp
final case class GroupAgg(columns: List[String], aggregations: List[(String, String)]) extends TableOp
final case class QuantileBin(column: String, binCount: Int, err: Double) extends TableOp
final case class Select(columns: List[String]) extends TableOp

// the standard structure to hold quartile data
final case class Quartiles(min: Double, q1: Double, median: Double, mean: Double, q3: Double, max: Double)
object Quartiles {
  val fields: List[StructField] = List("min", "q1", "median", "mean", "q3", "max").map(StructField(_, DoubleType))
  implicit val dataEncoder: DataEncoder.StructDataEncoder[Quartiles] =
    new runtime.DataEncoder.StructDataEncoder[Quartiles](StructType(fields)) {
      def field(name: String): Option[(Quartiles => Any, DataEncoder[_])] = name match {
        case "min"    => Some((_.min, DataEncoder.double))
        case "q1"     => Some((_.q1, DataEncoder.double))
        case "median" => Some((_.median, DataEncoder.double))
        case "mean"   => Some((_.mean, DataEncoder.double))
        case "q3"     => Some((_.q3, DataEncoder.double))
        case "max"    => Some((_.max, DataEncoder.double))
        case _ => None
      }
      def encode(dataOutput: DataOutput, value: Quartiles): Unit = {
        dataOutput.writeDouble(value.min)
        dataOutput.writeDouble(value.q1)
        dataOutput.writeDouble(value.median)
        dataOutput.writeDouble(value.mean)
        dataOutput.writeDouble(value.q3)
        dataOutput.writeDouble(value.max)
      }

      def sizeOf(t: Quartiles): Int = 6 * 8
    }
}

