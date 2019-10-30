package polynote.runtime.test

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.scalatest.{FreeSpec, Matchers}
import polynote.runtime.{DataEncoder, GroupAgg, ReprsOf, StreamingDataRepr}

class  CollectionReprsSpec extends FreeSpec with Matchers {

  "Streaming repr of structs" - {
    case class Example(label: String, i: Int, d: Double)

    "Aggregates correctly" - {
      "mean" in {
        val l = List(Example("a", 10, 10.0), Example("b", 11, 11.0), Example("c", 12, 12.0), Example("a", 12, 12.0))
        val de = implicitly[DataEncoder.StructDataEncoder[Example]]
        val h = ReprsOf.StructSeqStreamHandle[Example, Example](0, l, l => l, de)
        val Right(h1) = h.modify(List(GroupAgg(List("label"), List("i" -> "mean", "d" -> "mean")))).right.map(_.apply(1))

        def decode(buf: ByteBuffer) = {
          buf.rewind()
          val labelLength = buf.getInt()
          val labelArr = new Array[Byte](labelLength)
          buf.get(labelArr)
          val label = new String(labelArr, StandardCharsets.UTF_8)
          val avgI = buf.getDouble()
          val avgD = buf.getDouble()

          (label, avgI, avgD)
        }

        h1.iterator.map(decode).toList should contain theSameElementsAs List(
          ("a", 11.0, 11.0),
          ("b", 11.0, 11.0),
          ("c", 12.0, 12.0)
        )
      }

    }
  }

}
