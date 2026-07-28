package com.kbap.api.common.auth

import com.kbap.api.common.ApiPaths
import com.kbap.common.port.auth.TokenParser
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcAuthConfig(
    private val tokenParser: TokenParser,
) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(AuthMemberIdArgumentResolver())
        resolvers.add(AuthMemberIdOrNullArgumentResolver(tokenParser))
    }

    @Bean
    fun jwtAuthenticationFilterRegistration(): FilterRegistrationBean<JwtAuthenticationFilter> =
        FilterRegistrationBean(JwtAuthenticationFilter(tokenParser)).apply {
            addUrlPatterns(
                "${ApiPaths.V1}/members/*",
                "${ApiPaths.V1}/scans",
                "${ApiPaths.V1}/scans/*",
                "${ApiPaths.V1}/bookmarks",
                "${ApiPaths.V1}/bookmarks/*",
                "${ApiPaths.V1}/images",
                "${ApiPaths.V1}/images/*",
                "${ApiPaths.V1}/auth/withdraw",
                "${ApiPaths.ADMIN}/*",
            )
        }
}
