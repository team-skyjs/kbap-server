package com.kbap.api.core.config

import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder

@Configuration
class MockMvcDefaultVersionConfig {
    @Bean
    fun defaultApiVersionCustomizer(): MockMvcBuilderCustomizer =
        MockMvcBuilderCustomizer { builder ->
            (builder as DefaultMockMvcBuilder).defaultRequest<DefaultMockMvcBuilder>(
                MockMvcRequestBuilders.get("/").header("X-API-Version", "1.0"),
            )
        }
}
