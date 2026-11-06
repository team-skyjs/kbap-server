package com.kbap.api.core.config

import com.kbap.api.infra.jev.JevMenuPhotoClassifier
import com.kbap.common.port.llm.MenuPhotoClassifier
import com.kbap.common.port.storage.StorageObjectStore
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
class JevConfig {
    @Bean
    @ConditionalOnProperty(prefix = "kbap.processed-food.vision.menu-fast-path", name = ["enabled"], havingValue = "true")
    @Conditional(JevKeyPresent::class)
    fun menuPhotoClassifier(
        storage: StorageObjectStore,
        @Value("\${kbap.processed-food.vision.menu-fast-path.api-key}") apiKey: String,
        @Value("\${kbap.processed-food.vision.menu-fast-path.budget:1s}") budget: Duration,
        @Value("\${kbap.processed-food.vision.max-image-bytes:2097152}") maxImageBytes: Long,
    ): MenuPhotoClassifier = JevMenuPhotoClassifier(storage, apiKey, budget, maxImageBytes)

    class JevKeyPresent : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
            !context.environment.getProperty("kbap.processed-food.vision.menu-fast-path.api-key").isNullOrBlank()
    }
}
