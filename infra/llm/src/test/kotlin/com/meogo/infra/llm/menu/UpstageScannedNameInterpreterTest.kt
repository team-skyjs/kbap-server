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

        `when`("보낼 텍스트가 프롬프트에 포함되면") {
            then("각 원문이 프롬프트에 들어간다") {
                val caller = FakeCaller("""["돈까스"]""")
                val interpreter = UpstageScannedNameInterpreter(caller, ScannedNameParser())

                interpreter.interpret(listOf("돈까스 donkatsu"))

                (caller.lastRequest!!.prompt.contains("돈까스 donkatsu")) shouldBe true
            }
        }

        `when`("입력이 비어 있으면") {
            then("LLM 을 호출하지 않고 빈 결과를 반환한다") {
                val caller = FakeCaller("[]")
                val interpreter = UpstageScannedNameInterpreter(caller, ScannedNameParser())

                interpreter.interpret(emptyList()) shouldBe emptyList()
                caller.callCount shouldBe 0
            }
        }
    }
})
