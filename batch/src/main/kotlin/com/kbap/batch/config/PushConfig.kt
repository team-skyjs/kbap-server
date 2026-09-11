package com.kbap.batch.config

import com.kbap.common.domain.notification.PushDispatchService
import com.kbap.common.domain.notification.PushMessageRenderer
import com.kbap.common.domain.notification.PushTargetResolver
import com.kbap.common.infra.push.ExpoPushSender
import com.kbap.common.port.push.PushSender
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

@Configuration
@Import(PushDispatchService::class, PushTargetResolver::class, PushMessageRenderer::class)
class PushConfig {
    @Bean
    @ConditionalOnMissingBean(PushSender::class)
    fun pushSender(
        @Value("\${kbap.push.expo.base-url}") baseUrl: String,
        @Value("\${kbap.push.expo.access-token:}") accessToken: String,
    ): PushSender = ExpoPushSender.create(baseUrl, accessToken)
}
