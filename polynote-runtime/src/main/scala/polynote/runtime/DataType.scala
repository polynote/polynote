package polynote.runtime

sealed trait DataType

sealed trait PrimitiveType extends DataType

case object ByteType extends PrimitiveType
case object BoolType extends PrimitiveType
case object ShortType extends PrimitiveType
case object IntType extends PrimitiveType
case object LongType extends PrimitiveType
case object FloatType extends PrimitiveType
case object DoubleType extends PrimitiveType

case object BinaryType extends DataType
case object StringType extends DataType

final case class StructField(
  name: String,
  dataType: DataType
)

final case class StructType(
  fields: List[StructField]
) extends DataType

final case class OptionalType(underlying: DataType) extends DataType
final case class ArrayType(element: DataType) extends DataType