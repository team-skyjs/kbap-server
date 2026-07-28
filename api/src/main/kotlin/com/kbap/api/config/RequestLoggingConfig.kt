package com.kbap.api.config

import com.kbap.api.common.logging.RequestLoggingFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

@Configuration
class RequestLoggingConfig {
    @Bean
    fun requestLoggingFilterRegistration(): FilterRegistrationBean<RequestLoggingFilter> =
        FilterRegistrationBean(RequestLoggingFilter()).apply {
            // 인증 필터보다 먼저 실행돼야 401 거절 응답도 상관 키를 갖고, 소요 시간이 전체 처리를 덮는다.
            order = Ordered.HIGHEST_PRECEDENCE
            addUrlPatterns("/api/*")
        }
}