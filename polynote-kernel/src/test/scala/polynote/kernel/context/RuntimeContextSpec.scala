package polynote.kernel.context

import org.scalatest._

class RuntimeContextSpec extends FreeSpec with Matchers {

  val globalInfo = GlobalInfo(Map.empty, Nil)
  val predefCtx = RuntimeContext.getPredefContext(globalInfo)

  def mkSymbolEntry(name: String, value: Any, origin: String) = name -> ComparableRTV(name, value, origin).toRTV

  // this makes it easy to compare RTVs without fighting with types.
  case class ComparableRTV(name: String, value: Any, sourceCellId: String) {
    def toRTV = globalInfo.RuntimeValue(name, value, None, sourceCellId)
  }
  object ComparableRTV {
    def apply(rtv: GlobalInfo#RuntimeValue): ComparableRTV = ComparableRTV(rtv.name.toString, rtv.value, rtv.sourceCellId)
  }

  // some fixtures
  case class FooContext(foo: String) extends InterpreterContext
  case class BarContext(bar: String) extends InterpreterContext

  val cell1Symbols = Map(mkSymbolEntry("var1", 1, "cell1"), mkSymbolEntry("b", "banana", "cell1"))
  val cell1Ctx = RuntimeContext("cell1", globalInfo, Option(predefCtx), cell1Symbols, Option(FooContext("ctx1")), Option(ComparableRTV("resCell1", "one", "cell1").toRTV))

  val cell2Symbols = Map(mkSymbolEntry("var2", 2, "cell2"), mkSymbolEntry("b", "bonobo", "cell2"), mkSymbolEntry("kernel", "haha I overwrote the kernel!", "cell2"))
  val cell2Ctx = RuntimeContext("cell2", globalInfo, Option(cell1Ctx), cell2Symbols, Option(BarContext("ctx2")), Option(ComparableRTV("resCell2", "two", "cell2").toRTV))

  val cell3Symbols = Map(mkSymbolEntry("var2", 3, "cell3"), mkSymbolEntry("c", "cougar", "cell3"))
  val cell3Ctx = RuntimeContext("cell3", globalInfo, Option(cell2Ctx), cell3Symbols, Option(FooContext("ctx3")), Option(ComparableRTV("resCell3", "three", "cell3").toRTV))



  "A runtimeContext" - {
    "chains properly" in {
      val expectedResults = Map(
        "cell1" -> ComparableRTV("resCell1", "one", "cell1"),
        "cell2" -> ComparableRTV("resCell2", "two", "cell2"),
        "cell3" -> ComparableRTV("resCell3", "three", "cell3")
      )

      // use RTV directly so we can set the exact type of this one.
      val expectedOut = ComparableRTV("Out", expectedResults.mapValues(_.value), "")

      val expectedSymbols = Seq(
        ComparableRTV("var2", 3, "cell3"),
        ComparableRTV("c", "cougar", "cell3"),
        ComparableRTV("b", "bonobo", "cell2"),
        ComparableRTV("var1", 1, "cell1"),
        ComparableRTV("kernel", "haha I overwrote the kernel!", "cell2"),
        expectedOut
      )

      ComparableRTV(cell3Ctx.out) shouldEqual expectedOut
      cell3Ctx.visibleSymbols.map(ComparableRTV(_)) should contain theSameElementsAs expectedSymbols
      cell3Ctx.resultMap.mapValues(ComparableRTV(_)).toSeq should contain theSameElementsAs (predefCtx.resultMap.mapValues(ComparableRTV(_)) ++ expectedResults).toSeq
      cell3Ctx.availableContext[FooContext] should contain theSameElementsAs Seq(FooContext("ctx1"), FooContext("ctx3"))
      cell3Ctx.availableContext[BarContext] should contain theSameElementsAs Seq(BarContext("ctx2"))
    }

    "properly inserts and deletes entries up and down the chain" in {


      val newCtx = cell3Ctx.delete("cell2").get

      val expectedResults = Map(
        "cell1" -> ComparableRTV("resCell1", "one", "cell1"),
        "cell3" -> ComparableRTV("resCell3", "three", "cell3")
      )

      // use RTV directly so we can set the exact type of this one.
      val expectedOut = ComparableRTV("Out", expectedResults.mapValues(_.value), "")

      val expectedSymbols = Seq(
        ComparableRTV("var2", 3, "cell3"),
        ComparableRTV("c", "cougar", "cell3"),
        ComparableRTV("b", "banana", "cell1"),
        ComparableRTV("var1", 1, "cell1"),
        ComparableRTV("kernel", polynote.runtime.Runtime, "$Predef"),
        expectedOut
      )

      ComparableRTV(newCtx.out) shouldEqual expectedOut
      newCtx.visibleSymbols.map(ComparableRTV(_)) should contain theSameElementsAs expectedSymbols
      newCtx.resultMap.mapValues(ComparableRTV(_)).toSeq should contain theSameElementsAs (predefCtx.resultMap.mapValues(ComparableRTV(_)) ++ expectedResults).toSeq
      newCtx.availableContext[FooContext] should contain theSameElementsAs Seq(FooContext("ctx1"), FooContext("ctx3"))
      newCtx.availableContext[BarContext] shouldBe empty

      // make sure it works with no parents
      predefCtx.delete("$Predef") shouldEqual None
    }
  }


}
