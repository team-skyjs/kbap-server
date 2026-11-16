package com.kbap.common.infra.llm.ingredient

import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.infra.llm.claude.ClaudeChat
import com.kbap.common.port.llm.IngredientDecider
import com.kbap.common.port.llm.IngredientDecisionFood
import com.kbap.common.port.llm.IngredientDecisions

// 프롬프트·스키마·파싱은 OpenAI 구현과 공유한다 — 공급자만 다르다.
class ClaudeIngredientDecider(private val chat: ClaudeChat) : IngredientDecider {
    override fun decide(food: IngredientDecisionFood, codes: List<IngredientCode>): IngredientDecisions {
        require(codes.isNotEmpty()) { "판정할 재료 코드가 없다" }
        val reply = chat.ask(
            system = OpenAiIngredientDecider.SYSTEM_PROMPT,
            userText = OpenAiIngredientDecider.userMessage(food, codes),
            jsonSchema = OpenAiIngredientDecider.schemaOf(codes),
        )
        return IngredientDecisions(OpenAiIngredientDecider.parse(reply.text, codes), reply.cost.costKrw.toDouble())
    }
}
