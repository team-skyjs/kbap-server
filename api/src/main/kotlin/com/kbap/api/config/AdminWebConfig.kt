package com.kbap.api.config

import com.kbap.api.admin.AdminAuthorizationInterceptor
import com.kbap.api.common.ApiPaths
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class AdminWebConfig : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(AdminAuthorizationInterceptor())
            .addPathPatterns("${ApiPaths.ADMIN}/**")
    }
}
