package com.kbap.common.infra.llm.ingredient

import com.fasterxml.jackson.databind.ObjectMapper
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.domain.metering.LlmCallCostIncurred
import com.kbap.common.infra.llm.model.LlmPricing
import com.kbap.common.port.llm.IngredientDecider
import com.kbap.common.port.llm.IngredientDecisionFood
import com.kbap.common.port.llm.IngredientDecisions
import com.kbap.common.port.llm.IngredientPresence
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.math.RoundingMode

class OpenAiIngredientDecider(
    private val chatModel: ChatModel,
    private val pricing: LlmPricing,
    private val configuredModelName: String,
    private val eventPublisher: ApplicationEventPublisher,
) : IngredientDecider {
    override fun decide(food: IngredientDecisionFood, codes: List<IngredientCode>): IngredientDecisions {
        require(codes.isNotEmpty()) { "판정할 재료 코드가 없다" }
        val options = (chatModel.defaultOptions as? OpenAiChatOptions)?.mutate() ?: OpenAiChatOptions.builder()
        options.responseFormat(
            OpenAiChatModel.ResponseFormat.builder().type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA).jsonSchema(schemaOf(codes)).build(),
        )
        val response = chatModel.call(Prompt(listOf(SystemMessage(SYSTEM_PROMPT), UserMessage(userMessage(food, codes))), options.build()))
        val usage = response.metadata.usage
        val input = (usage.promptTokens ?: 0).toLong()
        val output = (usage.completionTokens ?: 0).toLong()
        val modelName = response.metadata.model?.takeIf { it.isNotBlank() } ?: configuredModelName
        val costKrw = pricing.costKrw(input, output)
        runCatching {
            eventPublisher.publishEvent(
                LlmCallCostIncurred(
                    modelName = modelName,
                    inputTokens = input,
                    outputTokens = output,
                    costUsd = BigDecimal.valueOf(pricing.costUsd(input, output)).setScale(6, RoundingMode.HALF_UP),
                    costKrw = BigDecimal.valueOf(costKrw).setScale(2, RoundingMode.HALF_UP),
                ),
            )
        }.onFailure { log.warn("재료 소급 판정 비용 이벤트 발행 실패", it) }
        return IngredientDecisions(parse(response.result?.output?.text.orEmpty(), codes), costKrw)
    }

    companion object {
        private val log = LoggerFactory.getLogger(OpenAiIngredientDecider::class.java)
        private val mapper = ObjectMapper()

        val SYSTEM_PROMPT: String = """
            한국에서 파는 음식 한 가지의 이름·설명과 재료 코드 목록이 주어진다.
            코드마다 probability 는 이 음식을 시켰을 때 그 재료가 들어 있을 가능성(0~1)이다.
            이 음식의 일반적인 조리법·고명·소스에 실제로 쓰이는 재료만 0 보다 큰 확률을 준다. 일반적인 조리법에 쓰이지 않으면 0 이다.
            가게마다 다를 수 있다는 이유만으로 확률을 주지 않는다.
            contains 는 보통 들어가면 true, reason 은 근거 한 줄이다.
            예: 반미의 CORIANDER(고수) — 보통 올라가므로 높다. 김치찌개의 CORIANDER(고수) — 쓰이지 않으므로 0.
            안전하다·먹어도 된다 같은 단정은 쓰지 않는다. 주어진 코드 모두에 한 번씩만 답한다.
        """.trimIndent()

        fun userMessage(food: IngredientDecisionFood, codes: List<IngredientCode>): String = buildString {
            appendLine("음식: ${food.koreanName}")
            appendLine("설명: ${food.description}")
            food.longDescription?.takeIf { it.isNotBlank() }?.let { appendLine("긴 설명: $it") }
            appendLine("현재 재료 코드: ${food.currentCodes.joinToString(", ").ifEmpty { "(없음)" }}")
            appendLine("판단할 재료:")
            codes.forEach { appendLine("${it.name}\t${it.label}") }
        }.trimEnd()

        fun schemaOf(codes: List<IngredientCode>): String = mapper.writeValueAsString(
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "decisions" to mapOf(
                        "type" to "array",
                        "items" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "code" to mapOf("type" to "string", "enum" to codes.map { it.name }),
                                "contains" to mapOf("type" to "boolean"),
                                "probability" to mapOf("type" to "number"),
                                "reason" to mapOf("type" to "string"),
                            ),
                            "required" to listOf("code", "contains", "probability", "reason"),
                            "additionalProperties" to false,
                        ),
                    ),
                ),
                "required" to listOf("decisions"),
                "additionalProperties" to false,
            ),
        )

        fun parse(text: String, codes: List<IngredientCode>): Map<IngredientCode, IngredientPresence> {
            val asked = codes.toSet()
            return mapper.readTree(text).path("decisions")
                .mapNotNull { node ->
                    val code = IngredientCode.entries.firstOrNull { it.name == node.path("code").asText() }?.takeIf { it in asked } ?: return@mapNotNull null
                    code to IngredientPresence(
                        contains = node.path("contains").asBoolean(),
                        probability = node.path("probability").asDouble().coerceIn(0.0, 1.0),
                        reason = node.path("reason").asText().trim(),
                    )
                }
                .distinctBy { it.first }
                .toMap()
        }
    }
}
