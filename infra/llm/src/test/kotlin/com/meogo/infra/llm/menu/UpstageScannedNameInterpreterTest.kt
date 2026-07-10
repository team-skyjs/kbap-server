package com.meogo.infra.llm.menu

import com.meogo.core.kernel.scan.InterpretedName
import com.meogo.infra.llm.client.LlmModelCaller
import com.meogo.infra.llm.model.LlmChatRequest
import com.meogo.infra.llm.model.LlmModelId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

private class FakeCaller(private val response: String) : LlmModelCaller {
    override val modelId = LlmModelId.UPSTAGE
    var callCount = 0
        private set
    var lastRequest: LlmChatRequest? = null
        private set

    override fun call(request: LlmChatRequest): String {
        callCount++
        lastRequest = request
        return response
    }
}

class UpstageScannedNameInterpreterTest : BehaviorSpec({
    given("UpstageScannedNameInterpreter") {
        `when`("여러 텍스트를 해석하면") {
            then("스캔당 1콜로 입력 순서대로 결과를 매핑한다") {
                val caller = FakeCaller("""["김치찌개","NOT_FOOD"]""")
                val interpreter = UpstageScannedNameInterpreter(caller, ScannedNameParser())

                val result = interpreter.interpret(listOf("김치찌개 kimchi", "원산지 중국"))

                result shouldBe listOf(
                    InterpretedName.StandardName("김치찌개"),
                    InterpretedName.NotFood,
                )
                caller.callCount shouldBe 1
            }
        }

        `when`("프롬프트를 조립하면") {
            then("원문을 번호와 함께 싣고 기대 개수를 명시한다") {
                val caller = FakeCaller("""["돈까스","NOT_FOOD"]""")
                val interpreter = UpstageScannedNameInterpreter(caller, ScannedNameParser())

                interpreter.interpret(listOf("돈까스 donkatsu", "원산지 중국"))

                val prompt = caller.lastRequest!!.prompt
                prompt.contains("1. 돈까스 donkatsu") shouldBe true
                prompt.contains("2. 원산지 중국") shouldBe true
                prompt.contains("exactly 2 strings") shouldBe true
                caller.lastRequest!!.system!!.contains("NOT_FOOD") shouldBe true
            }
        }
    }
})
