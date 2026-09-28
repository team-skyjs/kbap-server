package com.kbap.batch.config

import com.kbap.common.infra.slack.SlackWebhookSender
import com.kbap.common.port.teamchannel.TeamChannelSender
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TeamChannelConfig {
    @Bean
    @ConditionalOnMissingBean(TeamChannelSender::class)
    @ConditionalOnExpression("!'\${kbap.batch.user-stats.slack-webhook-url:}'.isBlank()")
    fun teamChannelSender(
        @Value("\${kbap.batch.user-stats.slack-webhook-url}") webhookUrl: String,
    ): TeamChannelSender = SlackWebhookSender(webhookUrl.trim())
}
