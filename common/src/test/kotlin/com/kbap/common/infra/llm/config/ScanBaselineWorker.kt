package com.kbap.common.infra.llm.config

import com.kbap.common.domain.metering.LlmCallCostIncurred
import org.springframework.context.ApplicationEventPublisher
import java.time.Duration
import kotlin.time.TimeSource

object ScanBaselineWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        evaluate(requireNotNull(System.getenv("OPENAI_API_KEY")), "https://api.openai.com/v1", generateSequence(::readlnOrNull)) {
            println(it)
            System.out.flush()
        }
    }

    internal fun evaluate(key: String, endpoint: String, paths: Sequence<String>, emit: (String) -> Unit) {
        var cost: LlmCallCostIncurred? = null
        val props = LlmModelProperties.VisionProps(
            apiKey = key,
            baseUrl = endpoint,
            imageBaseUrl = "https://d29c1cr2ng7w0.cloudfront.net",
            model = "gpt-5.6-luna",
            temperature = 1.0,
            maxRetries = 0,
            retryBudget = Duration.ZERO,
        )
        val reader = LlmConfiguration().menuBoardVisionExtractor(
            LlmModelProperties(vision = props),
            ApplicationEventPublisher { event -> cost = event as LlmCallCostIncurred },
        )
        paths.forEach { path ->
            require(path.matches(Regex("eval/scan-hint/v1/[a-f0-9]{64}\\.jpg")))
            cost = null
            val start = TimeSource.Monotonic.markNow()
            val reading = reader.read(path)
            val usage = requireNotNull(cost)
            emit(listOf("RESULT", reading.kind.name, usage.inputTokens, usage.outputTokens, usage.modelName, start.elapsedNow().inWholeMilliseconds).joinToString("\t"))
        }
    }
}
