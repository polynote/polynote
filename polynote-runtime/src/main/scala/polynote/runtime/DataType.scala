package polynote.runtime

sealed trait DataType extends Serializable {
  def size: Int = -1
}

sealed abstract class FixedSizeType(override val size: Int) extends DataType
sealed abstract class PrimitiveType(size: Int) extends FixedSizeType(size)

case object ByteType extends PrimitiveType(1)
case object BoolType extends PrimitiveType(1)
case object ShortType extends PrimitiveType(2)
case object IntType extends PrimitiveType(4)
case object LongType extends PrimitiveType(8)
case object FloatType extends PrimitiveType(4)
case object DoubleType extends PrimitiveType(8)

case object BinaryType extends DataType
case object StringType extends DataType

final case class StructField(
  name: String,
  dataType: DataType
)

final case class StructType(
  fields: List[StructField]
) extends DataType {
  override def size: Int = if (fields.isEmpty || fields.forall(_.dataType.size >= 0)) {
    fields.map(_.dataType.size).sum
  } else -1
}

final case class OptionalType(element: DataType) extends DataType {
  override val size: Int = if (element.size >= 0) element.size + 1 else -1
}

final case class ArrayType(element: DataType) extends DataType
final case class MapType(kv: StructType) extends DataType

//case object DateType extends FixedSizeType(4)
//case object TimestampType extends FixedSizeType(8)

case object TypeType extends DataType