package polynote.kernel.context

import org.scalatest._

class RuntimeContextSpec extends FreeSpec with Matchers {

  val globalInfo = GlobalInfo(Map.empty, Nil)

  "A runtimeContext" - {
    "can peek into the predef" in {

      val predef = RuntimeContext.getPredefContext(globalInfo)

      val cell1Symbols = Map("foo" -> globalInfo.RuntimeValue("foo", 1, None, "cell1"))
      val cell1Ctx = RuntimeContext("cell1", globalInfo, Option(predef), cell1Symbols, None, None)

      cell1Ctx.resultMap shouldEqual predef.resultMap
      cell1Ctx.availableContext shouldBe empty
      cell1Ctx.visibleSymbols should contain theSameElementsAs cell1Symbols.values ++: predef.visibleSymbols

    }
  }


}
