package polynote

import java.nio.charset.StandardCharsets

import cats.data.Ior
import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}
import scodec.{Attempt, Codec, DecodeResult, SizeBound, codecs}
import scodec.bits.{BitVector, ByteVector}
import codecs.{byte, int32, listOfN, string, uint16, uint32, uint8, variableSizeBytes, provide}

import shapeless.Lazy
import shapeless.tag.@@

import scala.collection.compat.immutable.ArraySeq
import scala.language.implicitConversions
import scala.reflect.{ClassTag, classTag}

package object messages {

  trait ShortTag  // denotes that the value is expected to be < Short.MaxValue elements in length

  type ShortString = String @@ ShortTag
  object ShortString extends (String => ShortString) {
    def apply(str: String): ShortString = if (str.length > Short.MaxValue)
      throw new RuntimeException("String length exceeds Short.MaxValue")
    else
      str.asInstanceOf[ShortString]

    def truncate(str: String): ShortString = if (str.length > Short.MaxValue)
      str.substring(0, Short.MaxValue - 1).asInstanceOf[ShortString]
    else
      str.asInstanceOf[ShortString]

    def unapply(str: ShortString): Option[String] = Option(str)
  }

  implicit def truncateShortString(str: String): ShortString = ShortString(
    if (str.length > Short.MaxValue) str.substring(0, Short.MaxValue) else str)

  implicit val shortStringCodec: Codec[ShortString] =
    variableSizeBytes(uint16, string(StandardCharsets.UTF_8)).xmap(ShortString, x => x)

  implicit val shortStringEncoder: Encoder[ShortString] = Encoder.encodeString.contramap(s => s)
  implicit val shortStringDecoder: Decoder[ShortString] = Decoder.decodeString.map(_.asInstanceOf[ShortString])

  type ShortList[A] = List[A] @@ ShortTag
  object ShortList {
    def apply[A](list: List[A]): ShortList[A] =
      if (list.size > Short.MaxValue)
        throw new RuntimeException("List length exceeds Short.MaxValue")
      else
        list.asInstanceOf[ShortList[A]]

    def of[A](elems: A*): ShortList[A] = apply(elems.toList)
  }

  implicit def shortListCodec[A](implicit ca: Lazy[Codec[A]]): Codec[ShortList[A]] =
    listOfN(uint16, ca.value).xmap(la => ShortList(la), sla => sla)
  
  implicit def shortListEncoder[A](implicit listEncoder: Encoder[List[A]]): Encoder[ShortList[A]] = listEncoder.contramap(l => l)
  implicit def shortListDecoder[A](implicit listDecoder: Decoder[List[A]]): Decoder[ShortList[A]] = listDecoder.map(l => ShortList(l))

  implicit def listString2ShortListTinyString(ls: List[String]): TinyList[TinyString] = TinyList(ls.map(TinyString(_)))

  trait TinyTag // denotes that the value is expected to be < 256 elements in length

  type TinyString = String @@ TinyTag
  object TinyString {
    def apply(str: String): TinyString = if (str.length > 255)
      throw new RuntimeException("String length exceeds 255") // TODO: should these truncate instead?
    else
      str.asInstanceOf[TinyString]

    def unapply(str: TinyString): Option[String] = Option(str)
  }


  implicit def truncateTinyString(str: String): TinyString = TinyString(
    if (str.length > 255) str.substring(0, 254) else str)

  implicit val tinyStringCodec: Codec[TinyString] =
    variableSizeBytes(uint8, string(StandardCharsets.UTF_8)).xmap(TinyString.apply, x => x)
  
  implicit val tinyStringEncoder: Encoder[TinyString] = Encoder.encodeString.contramap(s => s)
  implicit val tinyStringDecoder: Decoder[TinyString] = Decoder.decodeString.map(s => TinyString(s))
  implicit val tinyStringKeyEncoder: KeyEncoder[TinyString] = KeyEncoder.encodeKeyString.contramap(s => s)
  implicit val tinyStringKeyDecoder: KeyDecoder[TinyString] = KeyDecoder.decodeKeyString.map(s => TinyString(s))

  type TinyList[A] = List[A] @@ TinyTag

  object TinyList {
    def apply[A](list: List[A]): TinyList[A] = if (list.size > 255)
      throw new RuntimeException("List length exceeds 255")
    else
      list.asInstanceOf[TinyList[A]]
    def of[A](as: A*): TinyList[A] = apply(as.toList)
    def unapply[A](list: TinyList[A]): Option[List[A]] = Option(list)
  }

  implicit def truncateTinyList[A](list: List[A]): TinyList[A] = TinyList(list.take(255))

  implicit def tinyListCodec[A](implicit ca: Lazy[Codec[A]]): Codec[TinyList[A]] =
    listOfN(uint8, ca.value).xmap(la => TinyList(la), tla => tla)
  
  implicit def tinyListEncoder[A](implicit listEncoder: Encoder[List[A]]): Encoder[TinyList[A]] = listEncoder.contramap(l => l)
  implicit def tinyListDecoder[A](implicit listDecoder: Decoder[List[A]]): Decoder[TinyList[A]] = listDecoder.map(l => TinyList(l))

  type TinyMap[A, B] = Map[A, B] @@ TinyTag
  def TinyMap[A, B](map: Map[A, B]): TinyMap[A, B] = if (map.size > 255)
    throw new RuntimeException("Map length exceeds 255")
  else
    map.asInstanceOf[TinyMap[A, B]]

  def TinyMap[A, B](t: (A, B)*): TinyMap[A, B] = TinyMap(Map(t: _*))

  implicit def tinyMapCodec[A, B](implicit ca: Lazy[Codec[A]], cb: Lazy[Codec[B]]): Codec[TinyMap[A, B]] =
    listOfN(uint8, ca.value ~ cb.value).xmap(l => TinyMap(l.toMap), m => m.toList)

  implicit def tinyMapEncoder[A, B](implicit enc: Encoder[Map[A, B]]): Encoder[TinyMap[A, B]] = enc.contramap(m => m)
  implicit def tinyMapDecoder[A, B](implicit dec: Decoder[Map[A, B]]): Decoder[TinyMap[A, B]] = dec.map(m => TinyMap(m))

  type ShortMap[A, B] = Map[A, B] @@ ShortTag
  def ShortMap[A, B](map: Map[A, B]): ShortMap[A, B] = if (map.size > Short.MaxValue)
    throw new RuntimeException(s"Map length exceeds Short Max Value of ${Short.MaxValue}")
  else
    map.asInstanceOf[ShortMap[A, B]]

  def ShortMap[A, B](t: (A, B)*): ShortMap[A, B] = ShortMap(Map(t: _*))

  implicit def map2ShortMap[A, B](map: Map[A, B]): ShortMap[A, B] = ShortMap(map)

  implicit def shortMapCodec[A, B](implicit ca: Lazy[Codec[A]], cb: Lazy[Codec[B]]): Codec[ShortMap[A, B]] =
    listOfN(uint16, ca.value ~ cb.value).xmap(l => ShortMap(l.toMap), m => m.toList)

  implicit def shortMapEncoder[A, B](implicit enc: Encoder[Map[A, B]]): Encoder[ShortMap[A, B]] = enc.contramap(m => m)
  implicit def shortMapDecoder[A, B](implicit dec: Decoder[Map[A, B]]): Decoder[ShortMap[A, B]] = dec.map(m => ShortMap(m))

  // refined ByteVector type, to encode it with a 32-bit length frame
  // assumption: We will never be sending ByteVectors of more than 4GB over the wire!
  type ByteVector32 = ByteVector @@ ShortTag
  implicit def ByteVector32(byteVector: ByteVector): ByteVector32 = byteVector.asInstanceOf[ByteVector32]
  implicit val byteVector32Codec: Codec[ByteVector32] =
    scodec.codecs.variableSizeBytesLong(uint32, scodec.codecs.bytes).xmap(_.asInstanceOf[ByteVector32], v => v)

  implicit def iorCodec[A, B](implicit codecA: Codec[A], codecB: Codec[B]): Codec[Ior[A, B]] =
    scodec.codecs.discriminated[Ior[A, B]].by(byte)
    .|(0) { case Ior.Left(a) => a } (Ior.left) (codecA)
    .|(1) { case Ior.Right(b) => b } (Ior.right) (codecB)
    .|(2) { case Ior.Both(a, b) => (a, b) } ((Ior.both[A, B] _).tupled) (codecA ~ codecB)

  implicit def eitherCodec[A, B](implicit cguard: Lazy[Codec[Boolean]], ca: Lazy[Codec[A]], cb: Lazy[Codec[B]]): Codec[Either[A, B]] =
    scodec.codecs.either(cguard.value, ca.value, cb.value)

  // alias for Cell ID type so we can more easily change it (if needed)
  type CellID = Short
  def CellID(i: Int): CellID = i.toShort

  implicit def int2cellId(i: Int): CellID = i.toShort

  implicit def arrayCodec[A : ClassTag](implicit aCodec: Codec[A]): Codec[Array[A]] =
    int32.flatZip {
      len => new Codec[Array[A]] {
        def decode(bits: BitVector): Attempt[DecodeResult[Array[A]]] = scodec.Decoder.decodeCollect[Array, A](aCodec, Some(len))(bits)

        def encode(value: Array[A]): Attempt[BitVector] = scodec.Encoder.encodeSeq(aCodec)(ArraySeq.unsafeWrapArray(value))

        def sizeBound: SizeBound = (aCodec.sizeBound * len) + SizeBound(32, None)
      }
    }.xmap(_._2, arr => arr.length -> arr)
}
