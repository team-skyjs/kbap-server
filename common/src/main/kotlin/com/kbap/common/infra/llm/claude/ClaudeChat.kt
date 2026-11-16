package com.kbap.common.infra.llm.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.UrlImageSource
import com.fasterxml.jackson.databind.ObjectMapper
import com.kbap.common.domain.metering.LlmCallCostIncurred
import com.kbap.common.infra.llm.model.LlmPricing
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Claude Messages API 단일 호출 + 비용 이벤트 발행. 텍스트·비전 caller 5종이 공유한다(OpenAI 는 이미지 생성·임베딩만 남는다).
 * effort 는 OpenAI reasoning-effort 값을 그대로 받는다 — Claude 에 없는 "minimal" 은 "low" 로 내린다. 비우면 모델 기본값.
 * thinking 은 보내지 않는다(Sonnet·Haiku 5.5 는 adaptive 기본, budget_tokens 는 거부된다). 사고 토큰은 output_tokens 에 포함돼 과금된다.
 */
class ClaudeChat(
    private val client: AnthropicClient,
    private val model: String,
    private val effort: String?,
    private val pricing: LlmPricing,
    private val eventPublisher: ApplicationEventPublisher,
) {
    data class Reply(
        val text: String,
        val model: String,
        val inputTokens: Long,
        val outputTokens: Long,
        val stopReason: StopReason?,
        val cost: LlmCallCostIncurred,
    ) {
        val finished: Boolean get() = stopReason == StopReason.END_TURN
    }

    fun ask(
        system: String,
        userText: String,
        imageUrl: String? = null,
        jsonSchema: String? = null,
        maxTokens: Long = DEFAULT_MAX_TOKENS,
    ): Reply {
        val blocks = listOfNotNull(
            imageUrl?.let { ContentBlockParam.ofImage(UrlImageSource.builder().url(it).build()) },
            ContentBlockParam.ofText(userText),
        )
        val outputConfig = OutputConfig.builder().apply {
            effortOf(effort)?.let { effort(it) }
            jsonSchema?.let { format(formatOf(it)) }
        }.build()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .system(system)
            .addUserMessageOfBlockParams(blocks)
            .apply { if (effort != null || jsonSchema != null) outputConfig(outputConfig) }
            .build()
        val message = client.messages().create(params)
        val input = message.usage().inputTokens()
        val output = message.usage().outputTokens()
        val modelName = message.model().asString().takeIf { it.isNotBlank() } ?: model
        val cost = LlmCallCostIncurred(
            modelName = modelName,
            inputTokens = input,
            outputTokens = output,
            costUsd = BigDecimal.valueOf(pricing.costUsd(input, output)).setScale(6, RoundingMode.HALF_UP),
            costKrw = BigDecimal.valueOf(pricing.costKrw(input, output)).setScale(2, RoundingMode.HALF_UP),
        )
        runCatching { eventPublisher.publishEvent(cost) }.onFailure { log.warn("Claude 호출 비용 이벤트 발행 실패 modelName={}", modelName, it) }
        return Reply(
            text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(""),
            model = modelName,
            inputTokens = input,
            outputTokens = output,
            stopReason = message.stopReason().orElse(null),
            cost = cost,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(ClaudeChat::class.java)
        private val mapper = ObjectMapper()

        // 비스트리밍 호출의 기본 상한. 실제 길이는 각 caller 의 timeout 이 먼저 묶는다.
        const val DEFAULT_MAX_TOKENS = 16_000L

        fun effortOf(value: String?): OutputConfig.Effort? =
            when (value?.trim()?.lowercase()) {
                null, "" -> null
                "minimal", "none" -> OutputConfig.Effort.LOW
                else -> OutputConfig.Effort.of(value.trim().lowercase())
            }

        // 기존 OpenAI JSON_SCHEMA 문자열을 그대로 쓴다 — 모든 object 에 additionalProperties:false 가 이미 있다.
        fun formatOf(jsonSchema: String): JsonOutputFormat {
            val schema = JsonOutputFormat.Schema.builder()
            mapper.readValue(jsonSchema, Map::class.java).forEach { (k, v) -> schema.putAdditionalProperty(k as String, JsonValue.from(v)) }
            return JsonOutputFormat.builder().schema(schema.build()).build()
        }
    }
}
