package com.meogo.infra.llm.menu

import com.meogo.core.kernel.scan.InterpretedName
import com.meogo.core.kernel.scan.ScannedNameInterpreter
import com.meogo.infra.llm.client.LlmModelCaller
import com.meogo.infra.llm.model.LlmChatRequest

class UpstageScannedNameInterpreter(
    private val caller: LlmModelCaller,
    private val parser: ScannedNameParser,
) : ScannedNameInterpreter {
    override fun interpret(texts: List<String>): List<InterpretedName> {
        if (texts.isEmpty()) return emptyList()
        val response = caller.call(LlmChatRequest(prompt = userPrompt(texts), system = SYSTEM_PROMPT))
        return parser.parse(response, texts.size)
    }

    private fun userPrompt(texts: List<String>): String {
        val numbered = texts.withIndex().joinToString("\n") { (index, text) -> "${index + 1}. $text" }
        return "Input has ${texts.size} lines. Return a JSON array of exactly ${texts.size} strings.\n\n$numbered"
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "You normalize OCR text lines from a Korean restaurant menu.\n" +
                "\n" +
                "For each numbered input line, output one string:\n" +
                "- If the line names a food or drink item, output only its standard Korean name. " +
                "Strip romanization, prices, numbers, symbols, portion sizes, and trailing modifiers. Fix obvious typos.\n" +
                "- Otherwise output exactly \"NOT_FOOD\". " +
                "Non-food lines include origin labels, prices, category headers, notices, and UI or device text.\n" +
                "\n" +
                "Hard rules:\n" +
                "- The output array length MUST equal the number of input lines.\n" +
                "- Keep the input order. Never merge, split, reorder, or omit lines.\n" +
                "- Output ONLY the raw JSON array. No prose, no markdown, no code fences.\n" +
                "\n" +
                "Example input (3 lines):\n" +
                "1. 김치찌개 kimchi jjigae\n" +
                "2. 원산지 : 중국\n" +
                "3. 돈까스 8,000\n" +
                "Example output:\n" +
                "[\"김치찌개\",\"NOT_FOOD\",\"돈까스\"]"
    }
}
