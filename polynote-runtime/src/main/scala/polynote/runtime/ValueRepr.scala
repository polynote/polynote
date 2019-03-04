package polynote.runtime

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

  private[polynote] class Handle(val handle: Int, val dataType: DataType, lazyData: => ByteBuffer) {
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

  def apply(dataType: DataType, value: => ByteBuffer): LazyDataRepr = {
    val handleId = nextHandle.getAndIncrement()
    val handle = new Handle(handleId, dataType, value)
    handles.put(handleId, handle)
    val repr = LazyDataRepr(handleId, dataType)

    // if the repr object gets GC'ed, we can let the handle go as well
    Cleaner.create(repr, new Runnable {
      def run(): Unit = handles.remove(handleId)
    })

    repr
  }

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

  private[polynote] class Handle(
    val handle: Int,
    val dataType: DataType,
    val knownSize: Option[Int],
    iter: => Iterator[ByteBuffer]
  ) {
    @volatile private var finalizer: () => Unit = _
    @volatile private var releaseFlag: Int = 0

    private[polynote] lazy val iterator: Iterator[ByteBuffer] = iter

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
  }

  private val handles: ConcurrentHashMap[Int, Handle] = new ConcurrentHashMap()
  private val nextHandle: AtomicInteger = new AtomicInteger(0)
  private[polynote] def getHandle(handleId: Int): Option[Handle] = Option(handles.get(handleId))

  def apply(dataType: DataType, knownSize: Option[Int], lazyIter: => Iterator[ByteBuffer]): StreamingDataRepr = {
    val handleId = nextHandle.getAndIncrement()
    val handle = new Handle(handleId, dataType, knownSize, lazyIter)
    handles.put(handleId, handle)

    val repr = StreamingDataRepr(handleId, dataType, knownSize)

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

  def apply(dataType: DataType, knownSize: Int, lazyIter: => Iterator[ByteBuffer]): StreamingDataRepr =
    apply(dataType, Some(knownSize), lazyIter)

  def apply(dataType: DataType, lazyIter: => Iterator[ByteBuffer]): StreamingDataRepr =
    apply(dataType, None, lazyIter)

}

sealed trait ValueRepr



