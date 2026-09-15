package com.kbap.api.core.config

import com.kbap.common.domain.notification.PushDispatchService
import com.kbap.common.infra.push.ExpoPushNotifier
import com.kbap.common.infra.push.ExpoPushSender
import com.kbap.common.port.push.PushNotifier
import com.kbap.common.port.push.PushSender
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class PushConfig {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(PushSender::class)
    fun pushSender(
        @Value("\${kbap.push.expo.base-url}") baseUrl: String,
        @Value("\${kbap.push.expo.access-token:}") accessToken: String,
        @Value("\${kbap.push.expo.concurrency:6}") concurrency: Int,
        @Value("\${kbap.push.expo.min-request-interval:170ms}") minRequestInterval: Duration,
        @Value("\${kbap.push.expo.retry.max-retries:3}") maxRetries: Long,
        @Value("\${kbap.push.expo.retry.initial-delay:1s}") initialDelay: Duration,
        @Value("\${kbap.push.expo.retry.multiplier:2.0}") multiplier: Double,
    ): PushSender =
        ExpoPushSender.create(
            baseUrl,
            accessToken,
            concurrency,
            minRequestInterval,
            ExpoPushSender.defaultRetryPolicy(maxRetries, initialDelay, multiplier),
        )

    @Bean
    fun pushNotifier(dispatchService: PushDispatchService, pushSender: PushSender): PushNotifier =
        ExpoPushNotifier(dispatchService, pushSender)
}
