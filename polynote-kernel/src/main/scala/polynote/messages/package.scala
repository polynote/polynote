package polynote

import java.nio.charset.StandardCharsets

import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}
import scodec.Codec
import scodec.codecs.{listOfN, string, uint16, uint8, variableSizeBytes}
import shapeless.Lazy
import shapeless.tag.@@

import scala.language.implicitConversions

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
  }

  implicit val shortStringCodec: Codec[ShortString] =
    variableSizeBytes(uint16, string(StandardCharsets.UTF_8)).xmap(ShortString, x => x)

  implicit val shortStringEncoder: Encoder[ShortString] = Encoder.encodeString.contramap(s => s)
  implicit val shortStringDecoder: Decoder[ShortString] = Decoder.decodeString.map(_.asInstanceOf[ShortString])

  type ShortList[A] = List[A] @@ ShortTag
  def ShortList[A](list: List[A]): ShortList[A] = if (list.size > Short.MaxValue)
    throw new RuntimeException("List length exceeds Short.MaxValue")
  else
    list.asInstanceOf[ShortList[A]]

  implicit def shortListCodec[A](implicit ca: Lazy[Codec[A]]): Codec[ShortList[A]] =
    listOfN(uint16, ca.value).xmap(la => ShortList(la), sla => sla)
  
  implicit def shortListEncoder[A](implicit listEncoder: Encoder[List[A]]): Encoder[ShortList[A]] = listEncoder.contramap(l => l)
  implicit def shortListDecoder[A](implicit listDecoder: Decoder[List[A]]): Decoder[ShortList[A]] = listDecoder.map(l => ShortList(l))

  trait TinyTag // denotes that the value is expected to be < 256 elements in length

  type TinyString = String @@ TinyTag
  def TinyString(str: String): TinyString = if (str.length > 255)
    throw new RuntimeException("String length exceeds 255") // TODO: should these truncate instead?
  else
    str.asInstanceOf[TinyString]

  implicit def truncateTinyString(str: String): TinyString = TinyString(
    if (str.length > 255) str.substring(0, 254) else str)

  implicit val tinyStringCodec: Codec[TinyString] =
    variableSizeBytes(uint8, string(StandardCharsets.UTF_8)).xmap(TinyString, x => x)
  
  implicit val tinyStringEncoder: Encoder[TinyString] = Encoder.encodeString.contramap(s => s)
  implicit val tinyStringDecoder: Decoder[TinyString] = Decoder.decodeString.map(s => TinyString(s))
  implicit val tinyStringKeyEncoder: KeyEncoder[TinyString] = KeyEncoder.encodeKeyString.contramap(s => s)
  implicit val tinyStringKeyDecoder: KeyDecoder[TinyString] = KeyDecoder.decodeKeyString.map(s => TinyString(s))

  type TinyList[A] = List[A] @@ TinyTag
  def TinyList[A](list: List[A]): TinyList[A] = if (list.size > 255)
    throw new RuntimeException("List length exceeds 255")
  else
    list.asInstanceOf[TinyList[A]]

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

  implicit def tinyMapCodec[A, B](implicit ca: Lazy[Codec[A]], cb: Lazy[Codec[B]]): Codec[TinyMap[A, B]] =
    listOfN(uint8, ca.value ~ cb.value).xmap(l => TinyMap(l.toMap), m => m.toList)

  implicit def tinyMapEncoder[A, B](implicit enc: Encoder[Map[A, B]]): Encoder[TinyMap[A, B]] = enc.contramap(m => m)
  implicit def tinyMapDecoder[A, B](implicit dec: Decoder[Map[A, B]]): Decoder[TinyMap[A, B]] = dec.map(m => TinyMap(m))
}
