package polynote.kernel.util

import java.util.concurrent.atomic.AtomicLong
import java.util.function.{LongBinaryOperator, LongUnaryOperator}

import zio.{UIO, ZIO}

class LongRef private (inner: AtomicLong) extends Serializable {
  def get: UIO[Long] = ZIO.succeed(inner.get())
  def set(value: Long): UIO[Unit] = ZIO.succeed(inner.set(value))
  def lazySet(value: Long): UIO[Unit] = ZIO.succeed(inner.lazySet(value))
  def getAndSet(value: Long): UIO[Long] = ZIO.succeed(inner.getAndSet(value))
  val incrementAndGet: UIO[Long] = ZIO.succeed(inner.incrementAndGet())
  val getAndIncrement: UIO[Long] = ZIO.succeed(inner.getAndIncrement())
  val decrementAndGet: UIO[Long] = ZIO.succeed(inner.decrementAndGet())
  val getAndDecrement: UIO[Long] = ZIO.succeed(inner.getAndDecrement())
  def addAndGet(delta: Long): UIO[Long] = ZIO.succeed(inner.addAndGet(delta))
  def getAndAdd(delta: Long): UIO[Long] = ZIO.succeed(inner.getAndAdd(delta))
  def compareAndSet(expected: Long, newValue: Long): UIO[Boolean] = ZIO.succeed(inner.compareAndSet(expected, newValue))
  def weakCompareAndSet(expected: Long, newValue: Long): UIO[Boolean] = ZIO.succeed(inner.weakCompareAndSet(expected, newValue))
  def getAndUpdate(op: LongUnaryOperator): UIO[Long] = ZIO.succeed(inner.getAndUpdate(op))
  def getAndUpdate(op: Long => Long): UIO[Long] = getAndUpdate(LongRef.unaryOp(op))
  def updateAndGet(op: LongUnaryOperator): UIO[Long] = ZIO.succeed(inner.updateAndGet(op))
  def updateAndGet(op: Long => Long): UIO[Long] = updateAndGet(LongRef.unaryOp(op))
  def accumulateAndGet(value: Long, op: LongBinaryOperator): UIO[Long] = ZIO.succeed(inner.accumulateAndGet(value, op))
  def accumulateAndGet(value: Long, op: (Long, Long) => Long): UIO[Long] = accumulateAndGet(value, LongRef.binaryOp(op))
  def getAndAccumulate(value: Long, op: LongBinaryOperator): UIO[Long] = ZIO.succeed(inner.getAndAccumulate(value, op))
  def getAndAccumulate(value: Long, op: (Long, Long) => Long): UIO[Long] = getAndAccumulate(value, LongRef.binaryOp(op))
}

object LongRef {
  private def unaryOp(fn: Long => Long): LongUnaryOperator = new LongUnaryOperator {
    override final def applyAsLong(operand: Long): Long = fn(operand)
  }

  private def binaryOp(fn: (Long, Long) => Long): LongBinaryOperator = new LongBinaryOperator {
    override final def applyAsLong(left: Long, right: Long): Long = fn(left, right)
  }

  val zero: UIO[LongRef] = ZIO.succeed(zeroSync)
  def zeroSync: LongRef = makeSync(0L)

  def make(initial: Long): UIO[LongRef] = ZIO.succeed(makeSync(initial))
  def makeSync(initial: Long) = new LongRef(new AtomicLong(initial))

}
