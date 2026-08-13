package com.kbap.api.core.config

import com.kbap.api.admin.AdminAuthorizationInterceptor
import com.kbap.api.admin.AdminPageAuthInterceptor
import com.kbap.api.core.ApiPaths
import com.kbap.api.core.auth.AuthMemberIdArgumentResolver
import com.kbap.api.core.auth.AuthMemberIdOrNullArgumentResolver
import com.kbap.api.core.auth.JwtAuthenticationFilter
import com.kbap.api.core.logging.RequestLoggingFilter
import com.kbap.common.port.auth.TokenParser
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.accept.ApiVersionResolver
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(
    private val tokenParser: TokenParser,
) : WebMvcConfigurer {
    override fun configureApiVersioning(configurer: ApiVersionConfigurer) {
        configurer.useRequestHeader("X-API-Version")
            .useVersionResolver(exemptPathVersionResolver())
            .setVersionRequired(true)
    }

    private fun exemptPathVersionResolver() =
        ApiVersionResolver { request ->
            "1.0".takeIf {
                !request.requestURI.startsWith("${ApiPaths.API}/") ||
                    request.requestURI == ApiPaths.API + "/app-version"
            }
        }

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns("*")
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(AuthMemberIdArgumentResolver())
        resolvers.add(AuthMemberIdOrNullArgumentResolver(tokenParser))
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(AdminAuthorizationInterceptor())
            .addPathPatterns("${ApiPaths.ADMIN}/**")
        registry.addInterceptor(AdminPageAuthInterceptor(tokenParser))
            .addPathPatterns("/admin/**")
            .excludePathPatterns(AdminPageAuthInterceptor.LOGIN_PATH)
    }

    @Bean
    fun requestLoggingFilterRegistration(): FilterRegistrationBean<RequestLoggingFilter> =
        FilterRegistrationBean(RequestLoggingFilter()).apply {
            // 인증 필터보다 먼저 실행돼야 401 거절 응답도 상관 키를 갖고, 소요 시간이 전체 처리를 덮는다.
            order = Ordered.HIGHEST_PRECEDENCE
            addUrlPatterns("/api/*")
        }

    @Bean
    fun jwtAuthenticationFilterRegistration(): FilterRegistrationBean<JwtAuthenticationFilter> =
        FilterRegistrationBean(
            JwtAuthenticationFilter(
                tokenParser,
                // 게스트 열람 API — GET + 정확 일치 두 경로만. 더 깊은 경로(댓글 등)는 계속 보호된다.
                guestExemptions = listOf(
                    JwtAuthenticationFilter.GuestExemption("GET", Regex("^${ApiPaths.API}/community/posts$")),
                    JwtAuthenticationFilter.GuestExemption("GET", Regex("^${ApiPaths.API}/community/posts/\\d+$")),
                ),
            ),
        ).apply {
            addUrlPatterns(
                "${ApiPaths.API}/members/*",
                "${ApiPaths.API}/scans",
                "${ApiPaths.API}/scans/*",
                "${ApiPaths.API}/bookmarks",
                "${ApiPaths.API}/bookmarks/*",
                "${ApiPaths.API}/reviews",
                "${ApiPaths.API}/reviews/*",
                "${ApiPaths.API}/community/posts",
                "${ApiPaths.API}/community/posts/*",
                "${ApiPaths.API}/community/comments/*",
                "${ApiPaths.API}/reports",
                "${ApiPaths.API}/images",
                "${ApiPaths.API}/images/*",
                "${ApiPaths.API}/auth/withdraw",
                "${ApiPaths.ADMIN}/*",
            )
        }
}
