package com.kbap.api.core.config

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.kbap.api.infra.anthropic.AnthropicProcessedFoodNameDecider
import com.kbap.common.port.llm.ProcessedFoodNameDecider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
import java.time.Duration

@Configuration
class AnthropicConfig {
    @Bean
    @ConditionalOnProperty(prefix = "kbap.processed-food.name-scan.decision", name = ["enabled"], havingValue = "true")
    @Conditional(AnthropicKeyPresent::class)
    fun processedFoodNameDecider(
        @Value("\${kbap.processed-food.name-scan.decision.model:claude-haiku-5-5}") model: String,
        @Value("\${kbap.processed-food.name-scan.decision.timeout:3s}") timeout: Duration,
    ): ProcessedFoodNameDecider =
        AnthropicProcessedFoodNameDecider(
            AnthropicOkHttpClient.builder().fromEnv().timeout(timeout).maxRetries(0).build(),
            model,
        )

    class AnthropicKeyPresent : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
            !context.environment.getProperty("ANTHROPIC_API_KEY").isNullOrBlank()
    }
}
