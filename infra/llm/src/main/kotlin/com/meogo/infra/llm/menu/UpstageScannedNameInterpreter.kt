package com.meogo.infra.llm.menu

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.meogo.core.kernel.scan.InterpretedName
import com.meogo.core.kernel.scan.ScannedNameInterpreter
import com.meogo.infra.llm.client.LlmModelCaller
import com.meogo.infra.llm.model.LlmChatRequest

class UpstageScannedNameInterpreter(
    private val caller: LlmModelCaller,
    private val parser: ScannedNameParser,
) : ScannedNameInterpreter {
    private val objectMapper = jacksonObjectMapper()

    override fun interpret(texts: List<String>): List<InterpretedName> {
        if (texts.isEmpty()) return emptyList()
        val response = caller.call(LlmChatRequest(prompt = userPrompt(texts), system = SYSTEM_PROMPT))
        return parser.parse(response, texts.size)
    }

    private fun userPrompt(texts: List<String>): String =
        "다음 각 항목의 표준 한국어 메뉴명을 추출하라.\n" + objectMapper.writeValueAsString(texts)

    companion object {
        private const val SYSTEM_PROMPT =
            "너는 메뉴판 OCR 텍스트에서 표준 한국어 메뉴명을 뽑는 도구다. " +
                "입력은 문자열 JSON 배열이다. 각 원소마다 로마자 음역·가격·기호·오탈자를 제거한 표준 한국어 메뉴명을 반환하되, " +
                "음식 메뉴가 아니면 \"NOT_FOOD\" 를 반환한다. " +
                "반드시 입력과 같은 길이·같은 순서의 문자열 JSON 배열만 출력하고, 다른 설명은 붙이지 않는다."
    }
}
