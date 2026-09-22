package com.kbap.common.domain.notification

import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.support.ResourceBundleMessageSource

@Configuration
class PushMessageSourceConfig {
    @Bean
    fun messageSource(): MessageSource =
        ResourceBundleMessageSource().apply {
            setBasename("messages/push")
            setDefaultEncoding("UTF-8")
            setFallbackToSystemLocale(false)
        }
}
