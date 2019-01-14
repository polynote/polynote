package polynote.runtime

sealed trait DataType[T]

sealed trait PrimitiveType[T] extends DataType[T]

case object ByteType extends PrimitiveType[Byte]
case object BoolType extends PrimitiveType[Boolean]
case object ShortType extends PrimitiveType[Short]
case object IntType extends PrimitiveType[Int]
case object LongType extends PrimitiveType[Long]
case object FloatType extends PrimitiveType[Float]
case object DoubleType extends PrimitiveType[Double]

case object StringType extends DataType[String]

final case class OptionalType[T](underlying: DataType[T]) extends DataType[Option[T]]

final case class StructField[Name <: String, T](
  name: String,
  dataType: DataType[T]
)

final case class StructType[T](
  fields: List[StructField[_, _]]
) extends DataType[T]

final case class ListType[T](element: DataType[T]) extends DataType[List[T]]

final case class TableType[T](schema: StructType[T]) extends DataType[List[T]]

final case class LazyType[T](underlying: DataType[T]) extends DataType[() => T]

trait DataTypeOf[T] {
  type Out
  def apply(t: T): DataType[Out]
  def to(t: T): Out
}

object DataTypeOf {
  type Aux[T, Out0] = DataTypeOf[T] { type Out = Out0 }

  def instance[T, Out0](dt: T => DataType[Out0], toF: T => Out0): Aux[T, Out0] = new DataTypeOf[T] {
    type Out = Out0
    def apply(t: T): DataType[Out] = dt(t)
    def to(t: T): Out = toF(t)
  }

  def fixed[T](dataType: DataType[T]): Aux[T, T] = new DataTypeOf[T] {
    type Out = T
    def apply(t: T): DataType[Out] = dataType
    def to(t: T): T = t
  }

  implicit val byte: DataTypeOf[Byte] = fixed(ByteType)
  implicit val bool: DataTypeOf[Boolean] = fixed(BoolType)
  implicit val short: DataTypeOf[Short] = fixed(ShortType)
  implicit val int: DataTypeOf[Int] = fixed(IntType)
  implicit val long: DataTypeOf[Long] = fixed(LongType)
  implicit val float: DataTypeOf[Float] = fixed(FloatType)
  implicit val double: DataTypeOf[Double] = fixed(DoubleType)



}