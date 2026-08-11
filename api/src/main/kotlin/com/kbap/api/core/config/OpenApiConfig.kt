package com.kbap.api.core.config

import com.kbap.api.core.auth.AuthMemberId
import com.kbap.api.core.auth.AuthMemberIdOrNull
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OperationCustomizer
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

@Configuration
class OpenApiConfig {
    init {
        SpringDocUtils.getConfig()
            .addAnnotationsToIgnore(AuthMemberId::class.java)
            .addAnnotationsToIgnore(AuthMemberIdOrNull::class.java)
    }

    @Bean
    fun openApi(): OpenAPI =
        OpenAPI().components(
            Components().addSecuritySchemes(
                BEARER_AUTH,
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT"),
            ),
        )

    @Bean
    fun apiVersionHeaderCustomizer(
        handlerMappings: ObjectProvider<RequestMappingHandlerMapping>,
    ): OperationCustomizer {
        val declaredVersions by lazy {
            handlerMappings.stream().toList()
                .flatMap { it.handlerMethods.entries }
                .mapNotNull { (info, method) -> info.versionCondition?.version?.let { method to it } }
                .toMap()
        }
        return OperationCustomizer { operation, handlerMethod ->
            val declared = declaredVersions[handlerMethod]
            operation.addParametersItem(
                Parameter()
                    .`in`("header")
                    .name(API_VERSION_HEADER)
                    .required(false)
                    .description(versionDescription(declared))
                    .schema(StringSchema().apply { declared?.let { _default(it.removeSuffix("+")) } }),
            )
        }
    }

    companion object {
        const val BEARER_AUTH: String = "bearerAuth"
        const val API_VERSION_HEADER: String = "X-API-Version"

        private fun versionDescription(declared: String?): String =
            if (declared == null) {
                "요청 API 버전. 이 오퍼레이션은 버전을 가리지 않으므로 보내지 않아도 된다. 미전송이면 기본 1.0 으로 해석한다."
            } else {
                "요청 API 버전. **이 오퍼레이션은 `$declared` 에서만 응답한다** — 값을 비우면 기본 1.0 으로 해석돼 다른 버전의 핸들러가 처리한다. 매핑에 없는 버전은 400."
            }
    }
}
