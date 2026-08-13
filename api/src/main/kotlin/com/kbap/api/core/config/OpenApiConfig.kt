package com.kbap.api.core.config

import com.kbap.api.core.auth.AuthMemberId
import com.kbap.api.core.auth.AuthMemberIdOrNull
import com.kbap.api.core.logging.RequestLoggingFilter
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.security.SecurityScheme
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Method
import org.springdoc.core.customizers.OperationCustomizer
import org.springdoc.core.models.GroupedOpenApi
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.method.HandlerMethod
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
        OpenAPI().info(
            Info().title("kbap API").description(NOTICE),
        ).components(
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
            routesOf(handlerMappings).associate { it.handlerMethod to it.declaredVersion }
        }
        return OperationCustomizer { operation, handlerMethod ->
            if (operation.parameters.orEmpty().none { it.name == API_VERSION_HEADER }) {
                val declared = declaredVersions[handlerMethod.method]
                operation.addParametersItem(
                    Parameter()
                        .`in`("header")
                        .name(API_VERSION_HEADER)
                        .required(false)
                        .description(versionDescription(declared))
                        .schema(StringSchema().apply { declared?.let { _default(it.removeSuffix("+")) } }),
                )
            }
            operation
        }
    }

    @Bean
    fun clientVersionHeadersCustomizer(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            if (!handlerMethod.beanType.packageName.startsWith(ADMIN_PACKAGE)) {
                CLIENT_VERSION_HEADERS.forEach { (name, description) ->
                    if (operation.parameters.orEmpty().none { it.name == name }) {
                        operation.addParametersItem(
                            Parameter()
                                .`in`("header")
                                .name(name)
                                .required(false)
                                .description(description)
                                .schema(StringSchema()),
                        )
                    }
                }
            }
            operation
        }

    @Bean
    fun apiVersion10Doc(
        handlerMappings: ObjectProvider<RequestMappingHandlerMapping>,
        operationCustomizers: List<OperationCustomizer>,
    ): GroupedOpenApi = versionDoc("1.0", handlerMappings, operationCustomizers)

    @Bean
    fun apiVersion11Doc(
        handlerMappings: ObjectProvider<RequestMappingHandlerMapping>,
        operationCustomizers: List<OperationCustomizer>,
    ): GroupedOpenApi = versionDoc("1.1", handlerMappings, operationCustomizers)

    @Bean
    fun apiVersion20Doc(
        handlerMappings: ObjectProvider<RequestMappingHandlerMapping>,
        operationCustomizers: List<OperationCustomizer>,
    ): GroupedOpenApi = versionDoc("2.0", handlerMappings, operationCustomizers)

    private fun versionDoc(
        version: String,
        handlerMappings: ObjectProvider<RequestMappingHandlerMapping>,
        operationCustomizers: List<OperationCustomizer>,
    ): GroupedOpenApi {
        val served by lazy { servedMethods(version, handlerMappings) }
        return GroupedOpenApi.builder()
            .group(version)
            .displayName("X-API-Version $version")
            .addOpenApiMethodFilter { it in served }
            .apply { operationCustomizers.forEach { addOperationCustomizer(it) } }
            .build()
    }

    private fun servedMethods(
        version: String,
        handlerMappings: ObjectProvider<RequestMappingHandlerMapping>,
    ): Set<Method> =
        routesOf(handlerMappings)
            .groupBy { it.path to it.httpMethod }
            .values
            .mapNotNull { candidates -> winnerOf(version, candidates) }
            .toSet()

    private fun winnerOf(version: String, candidates: List<Route>): Method? =
        candidates
            .filter { serves(it.declaredVersion, version) }
            .maxByOrNull { specificityOf(it.declaredVersion, version) }
            ?.handlerMethod

    private fun routesOf(handlerMappings: ObjectProvider<RequestMappingHandlerMapping>): List<Route> =
        handlerMappings.stream().toList()
            .flatMap { it.handlerMethods.entries }
            .flatMap { (info, handler) ->
                val declared = declaredVersionOf(handler)
                info.patternValues.flatMap { path ->
                    info.methodsCondition.methods.ifEmpty { setOf<RequestMethod?>(null) }.map { httpMethod ->
                        Route(path, httpMethod, declared, handler.method)
                    }
                }
            }

    private fun declaredVersionOf(handler: HandlerMethod): String? =
        sequenceOf(handler.method as AnnotatedElement, handler.beanType)
            .mapNotNull { AnnotatedElementUtils.findMergedAnnotation(it, RequestMapping::class.java)?.version }
            .firstOrNull { it.isNotEmpty() }

    private data class Route(
        val path: String,
        val httpMethod: RequestMethod?,
        val declaredVersion: String?,
        val handlerMethod: Method,
    )

    companion object {
        const val BEARER_AUTH: String = "bearerAuth"
        const val API_VERSION_HEADER: String = "X-API-Version"
        private const val ADMIN_PACKAGE: String = "com.kbap.api.admin"
        private val NOTICE: String = """
            ## 공지
            ### 공통 헤더
            | 헤더 | 필수 | 용도 |
            |------|------|------|
            | `X-API-Version` | **필수** | 응답 계약 선택. 누락·미지원 버전은 400(COMMON-002). 유일 예외: `GET /api/app-version` |
            | `X-OS-Version` | 선택 | 클라이언트 OS·버전(예: iOS `iOS 18.1`, AOS `AOS 14`) — 서버 로그 분석용. **모든 요청에 넣어 보내주세요** |
            | `X-App-Version` | 선택 | 클라이언트 앱 버전(예: `2.3.0`) — 서버 로그 분석용. **모든 요청에 넣어 보내주세요** |

            응답의 `X-Request-Id` 헤더는 서버가 부여하는 요청 상관 키입니다 — 문의 시 함께 전달하면 로그 추적이 빠릅니다.

            버전별 계약 차이는 우측 상단 그룹 선택(`X-API-Version 1.0/1.1/2.0`)으로 확인하세요.
            """.trimIndent()
        private val CLIENT_VERSION_HEADERS: Map<String, String> = mapOf(
            RequestLoggingFilter.OS_VERSION_HEADER to
                "클라이언트 OS·버전(예: iOS `iOS 18.1`, AOS `AOS 14`). 로깅 전용 선택 헤더 — 보내지 않아도 동작한다.",
            RequestLoggingFilter.APP_VERSION_HEADER to
                "클라이언트 앱 버전(예: 2.3.0). 로깅 전용 선택 헤더 — 보내지 않아도 동작한다.",
        )

        private fun versionDescription(declared: String?): String = when {
            declared == null ->
                "요청 API 버전. 이 오퍼레이션은 버전을 가리지 않으므로 보내지 않아도 된다. 미전송이면 기본 1.0 으로 해석한다."
            declared.endsWith("+") ->
                "요청 API 버전. **이 오퍼레이션은 `${declared.removeSuffix("+")}` 이상에서 응답한다** — 값을 비우면 기본 1.0 으로 해석돼 다른 버전의 핸들러가 처리한다. 매핑에 없는 버전은 400."
            else ->
                "요청 API 버전. **이 오퍼레이션은 `$declared` 에서만 응답한다** — 값을 비우면 기본 1.0 으로 해석돼 다른 버전의 핸들러가 처리한다. 매핑에 없는 버전은 400."
        }

        private fun serves(declared: String?, version: String): Boolean = when {
            declared == null -> true
            declared.endsWith("+") -> compareVersions(version, declared.removeSuffix("+")) >= 0
            else -> declared == version
        }

        private fun specificityOf(declared: String?, version: String): Int = when {
            declared == null -> -1
            !declared.endsWith("+") && declared == version -> Int.MAX_VALUE
            else -> declared.removeSuffix("+").split(".").fold(0) { acc, part -> acc * 1000 + (part.toIntOrNull() ?: 0) }
        }

        private fun compareVersions(left: String, right: String): Int {
            val leftParts = left.split(".")
            val rightParts = right.split(".")
            repeat(maxOf(leftParts.size, rightParts.size)) { index ->
                val diff = (leftParts.getOrNull(index)?.toIntOrNull() ?: 0)
                    .compareTo(rightParts.getOrNull(index)?.toIntOrNull() ?: 0)
                if (diff != 0) return diff
            }
            return 0
        }
    }
}
