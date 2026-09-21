package com.kbap.api.core.config

import com.kbap.common.domain.notification.PushDispatchService
import com.kbap.common.infra.push.ExpoPushHandler
import com.kbap.common.infra.push.ExpoPushClient
import com.kbap.common.port.push.PushHandler
import com.kbap.common.port.push.PushClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class PushConfig {
    @Bean
    @ConditionalOnMissingBean(PushClient::class)
    fun pushClient(
        @Value("\${kbap.push.expo.base-url}") baseUrl: String,
        @Value("\${kbap.push.expo.access-token:}") accessToken: String,
        @Value("\${kbap.push.expo.retry.max-retries:3}") maxRetries: Long,
        @Value("\${kbap.push.expo.retry.initial-delay:1s}") initialDelay: Duration,
        @Value("\${kbap.push.expo.retry.multiplier:2.0}") multiplier: Double,
    ): PushClient =
        ExpoPushClient.create(
            baseUrl,
            accessToken,
            ExpoPushClient.defaultRetryPolicy(maxRetries, initialDelay, multiplier),
        )

    @Bean
    fun pushHandler(dispatchService: PushDispatchService, pushClient: PushClient): PushHandler =
        ExpoPushHandler(dispatchService, pushClient)
}
