package polynote.data

import org.scalacheck.Gen
import org.scalatest._
import org.scalatest.prop.PropertyChecks

class RopeSpec extends FlatSpec with Matchers with PropertyChecks {

  val r = new scala.util.Random(System.nanoTime())
  val aLongString: String = r.nextString(Rope.thresh * 10)
  val aLongRope = Rope(aLongString)

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfig(minSize = 0, maxSize = aLongRope.size, minSuccessful = 100, maxDiscarded = 100)

  "A Rope" should "convert properly" in {
    Rope(aLongString).toString shouldEqual aLongString
    Rope("").toString shouldEqual ""

    Rope("").toList shouldEqual Nil
    val expectedGroupSize = Stream.iterate(aLongString.length)(_ / 2).takeWhile(_ > Rope.thresh).last / 2
    aLongRope.toList should contain theSameElementsAs aLongString.toCharArray.grouped(expectedGroupSize).toList
  }

  it should "have the same size as the string representation" in {
    Rope(aLongString).size shouldEqual aLongString.length
  }

  it should "have the expected depth" in {
    Rope("").depth shouldEqual 0
    Rope("a").depth shouldEqual 0
    Rope(r.nextString(Rope.thresh)).depth shouldEqual 0

    // TODO: any way to make this faster?
    forAll(Gen.choose(1, 1024), minSuccessful(10)) { n: Int =>
      Rope(r.nextString(Rope.thresh * n + 1)).depth shouldEqual 1 + log2(n)
    }
  }

  it should "successfully grab the right char" in {
    forAll(Gen.choose(1, aLongRope.size - 1)) { n: Int =>
      aLongRope.charAt(n) shouldEqual aLongString.charAt(n)
    }

    a[StringIndexOutOfBoundsException] should be thrownBy aLongRope.charAt(aLongRope.size)
    a[StringIndexOutOfBoundsException] should be thrownBy aLongRope.charAt(-1)
  }

  it should "correctly split into two equal ropes" in {
    // we want to focus in on interesting values
    def focusedGen = Gen.choose(- aLongRope.size * 2, aLongRope.size * 2)

    forAll(focusedGen) { n: Int =>
      val (lRope, rRope) = aLongRope.splitAt(n)
      val (lStr, rStr) = aLongString.splitAt(n)

      lRope.toString shouldEqual lStr
      rRope.toString shouldEqual rStr
    }
  }

  it should "support insertions" in {
    var hardWorkingRope = aLongRope

    forAll(Gen.choose(0, hardWorkingRope.size), minSuccessful(10)) { n: Int =>
      val newRope = hardWorkingRope.insertAt(n, aLongRope)
      newRope shouldEqual hardWorkingRope.toString.patch(n, aLongRope.toString, 0)
      newRope.isBalanced shouldBe true
      hardWorkingRope += newRope
    }
    hardWorkingRope.isBalanced shouldBe true
  }

  it should "support deletions" in {
    // probably a better way to do this
    def deleteSlice(s: String, start: Int, length: Int): String = {
      s.zipWithIndex.flatMap {
        case (c, _) if length < 0 => Option(c)
        case (_, i) if start < 0 && i < length => None
        case (_, i) if i >= start && i < start.toLong + length => None
        case (c, _) => Option(c)
      }.mkString
    }

    forAll { (start: Int, length: Int) =>
      val newRope = aLongRope.delete(start, length)
      val newString = deleteSlice(aLongRope.toString, start, length)
      newRope shouldEqual newString
    }
  }

  it should "correctly concatenate" in {
    val gen = Gen.choose(0, Rope.thresh * 10)

    forAll(gen, gen) { (x: Int, y: Int) =>
      val str1 = r.nextString(x)
      val str2 = r.nextString(y)

      (Rope(str1) + Rope(str2)) shouldEqual str1 + str2
      (Rope(str1) + str2) shouldEqual str1 + str2
      (Rope(str1) + str2.toCharArray) shouldEqual str1 + str2
      whenever (str2.nonEmpty) {
        (Rope(str1) + str2.charAt(0)) shouldEqual str1 + str2.charAt(0)
      }
    }
  }

  it should "correctly substring" in {
    def substringGen = for {
      start <- Gen.choose(0, aLongString.length)
      length <- Gen.choose(0, aLongString.length - start)
    } yield (start, length)

    forAll(substringGen) { case (start: Int, _) =>
      aLongRope.substring(start) shouldEqual aLongString.substring(start)
    }

    forAll(substringGen) { case (start: Int, length: Int) =>
      aLongRope.substring(start, length) shouldEqual aLongString.substring(start, start + length)
    }
  }

  it should "correctly implement equals" in {
    // base case
    aLongRope should not equal new Object()

    forAll(Gen.choose(1, Rope.thresh * 10)) { n: Int =>
      val rand = r.nextString(n)
      val randRope = Rope(rand)

      randRope shouldEqual rand
      randRope shouldEqual Rope(rand)
      randRope shouldEqual Rope(rand.dropRight(1)) + Rope(rand.last)
      randRope should not equal Rope(rand.dropRight(1))
      randRope should not equal r.nextString(n)
    }
  }

  it should "map" in {
    def plusOne(c: Char): Char = (c.toInt + 1).toChar

    forAll(Gen.choose(1, Rope.thresh * 10)) { n: Int =>
      val rand = r.nextString(n)
      val randRope = Rope(rand)

      randRope.map(plusOne) shouldEqual rand.map(plusOne)
    }
  }

  it should "flatMap" in {
    def plusOne(c: Char): Char = (c.toInt + 1).toChar

    forAll(Gen.choose(1, Rope.thresh * 10)) { n: Int =>
      val rand = r.nextString(n)
      val randRope = Rope(rand)

      randRope.flatMap(c => Rope(plusOne(c))) shouldEqual rand.flatMap(plusOne(_).toString)
    }
  }

  it should "filter" in {
    def filterFun(c: Char): Boolean = c.toInt > Character.MAX_VALUE / 2

    forAll(Gen.choose(1, Rope.thresh * 10)) { n: Int =>
      val rand = r.nextString(n)
      val randRope = Rope(rand)

      randRope.filter(filterFun) shouldEqual rand.filter(filterFun)
    }
  }

  it should "foreach" in {
    forAll(Gen.choose(1, Rope.thresh * 10)) { n: Int =>
      val rand = Rope(r.nextString(n))
      val s = new StringBuilder()

      rand.foreach { c =>
        s += c
      }

      rand shouldEqual s.mkString
    }
  }

  it should "fold" in {
    forAll(Gen.choose(1, Rope.thresh * 10)) { n: Int =>
      val rand = Rope(r.nextString(n))

      rand shouldEqual rand.foldLeft(new StringBuilder) { case (sb, c) =>
          sb += c
      }.mkString

      rand shouldEqual rand.foldRight(new StringBuilder) { case (c, sb) =>
        sb += c
      }.mkString.reverse
    }
  }

  // better way to test this?
  it should "correctly report balance" in {
    RopeConcat(
      RopeConcat(
        RopeConcat(
          RopeConcat(
            RopeConcat(
              RopeConcat(RopeEmpty,
                RopeEmpty),
              RopeEmpty),
            RopeEmpty),
          RopeEmpty),
        RopeEmpty),
      RopeEmpty
    ).isBalanced shouldBe false

    RopeConcat(RopeEmpty, RopeEmpty).isBalanced shouldBe true
  }

  private def log2(x: Int) = (math.log(x) / math.log(2)).toInt
}
