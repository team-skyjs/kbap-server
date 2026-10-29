package com.kbap.api.infra.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.UrlImageSource
import com.fasterxml.jackson.databind.ObjectMapper
import com.kbap.common.port.llm.NameCandidate
import com.kbap.common.port.llm.NameDecision
import com.kbap.common.port.llm.NameDecisionRefused
import com.kbap.common.port.llm.ProcessedFoodNameDecider

class AnthropicProcessedFoodNameDecider(
    private val client: AnthropicClient,
    private val model: String,
) : ProcessedFoodNameDecider {
    override fun decide(imageUrl: String, candidates: List<NameCandidate>): NameDecision {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_OUTPUT_TOKENS)
            .system(SYSTEM_PROMPT)
            .addUserMessageOfBlockParams(
                listOf(
                    ContentBlockParam.ofImage(UrlImageSource.builder().url(imageUrl).build()),
                    ContentBlockParam.ofText(candidateLines(candidates)),
                ),
            )
            .outputConfig(OutputConfig.builder().format(OUTPUT_FORMAT).build())
            .build()
        return parse(client.messages().create(params), candidates.size)
    }

    companion object {
        private const val MAX_OUTPUT_TOKENS = 200L
        private val mapper = ObjectMapper()

        val SYSTEM_PROMPT: String = """
            한국 가공식품 포장 앞면 사진과 후보 목록이 주어진다. 후보는 한 줄에 하나씩 "id<TAB>제품명<TAB>제조사"다.
            사진 속 제품과 같은 제품인 후보의 id 를 choice 로 고른다. 같은 제품이 없으면 choice 는 null 이다.
            confidence 는 고른 답이 맞을 확률(0~1)이다. choice 가 null 이면 후보 중 정답이 없다는 판단의 확률이다.
            readName 은 포장에 인쇄된 제품명을 한국어 그대로 옮겨 적는다. 읽을 수 없으면 null 이다.
            성분·알레르기·안전에 대해서는 판단하지도 말하지도 않는다.
        """.trimIndent()

        private val OUTPUT_FORMAT: JsonOutputFormat = JsonOutputFormat.builder()
            .schema(
                JsonOutputFormat.Schema.builder()
                    .putAdditionalProperty("type", JsonValue.from("object"))
                    .putAdditionalProperty(
                        "properties",
                        JsonValue.from(
                            mapOf(
                                "choice" to mapOf("anyOf" to listOf(mapOf("type" to "integer"), mapOf("type" to "null"))),
                                "confidence" to mapOf("type" to "number"),
                                "readName" to mapOf("anyOf" to listOf(mapOf("type" to "string"), mapOf("type" to "null"))),
                            ),
                        ),
                    )
                    .putAdditionalProperty("required", JsonValue.from(listOf("choice", "confidence", "readName")))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build(),
            )
            .build()

        fun candidateLines(candidates: List<NameCandidate>): String =
            candidates.mapIndexed { i, c -> "${i + 1}\t${c.name}\t${c.maker.orEmpty()}" }.joinToString("\n")

        fun parse(message: Message, candidateCount: Int): NameDecision {
            if (message.stopReason().orElse(null) == StopReason.REFUSAL) throw NameDecisionRefused()
            val text = message.content().firstNotNullOfOrNull { it.text().orElse(null)?.text() }
                ?: error("판별 응답에 text 블록이 없다(stop_reason=${message.stopReason().orElse(null)})")
            return parseJson(text, candidateCount, message.model().asString(), message.usage().inputTokens(), message.usage().outputTokens())
        }

        fun parseJson(text: String, candidateCount: Int, model: String, inputTokens: Long, outputTokens: Long): NameDecision {
            val node = mapper.readTree(text)
            val choice = node.path("choice").takeIf { it.isIntegralNumber }?.asInt()
            return NameDecision(
                choiceIndex = choice?.takeIf { it in 1..candidateCount }?.minus(1),
                confidence = node.path("confidence").asDouble().coerceIn(0.0, 1.0),
                readName = node.path("readName").takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() },
                model = model,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
            )
        }
    }
}
