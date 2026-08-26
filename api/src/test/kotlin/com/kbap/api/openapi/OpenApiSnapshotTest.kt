package com.kbap.api.openapi

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.springdoc.core.models.GroupedOpenApi
import java.io.File
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class OpenApiSnapshotTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var groupedOpenApis: List<GroupedOpenApi>

    @Autowired
    private lateinit var handlerMappings: List<RequestMappingHandlerMapping>

    private val objectMapper = jacksonObjectMapper()

    private fun docOf(url: String) =
        objectMapper.readTree(
            mockMvc.get(url)
                .andExpect { status { isOk() } }
                .andReturn().response.contentAsString,
        )

    private fun versionParamsOf(operation: com.fasterxml.jackson.databind.JsonNode) =
        operation.path("parameters").filter { it.path("name").asText() == "X-API-Version" }

    // CI 릴리즈 파이프라인(release-notes.yml)이 이 파일을 oasdiff 입력으로 소비한다 — 경로 변경 시 워크플로도 함께 수정.
    private val snapshotFile = File("build/openapi.json")

    init {
        given("springdoc OpenAPI 문서") {
            `when`("/v3/api-docs 를 조회하면") {
                then("유효한 OpenAPI 문서가 build/openapi.json 스냅샷으로 남는다") {
                    val body = mockMvc.get("/v3/api-docs")
                        .andExpect { status { isOk() } }
                        .andReturn().response.contentAsString

                    val document = objectMapper.readTree(body)
                    document.path("openapi").asText().shouldStartWith("3.")
                    document.path("paths").properties().iterator().hasNext().shouldBeTrue()

                    snapshotFile.parentFile.mkdirs()
                    snapshotFile.writeText(body)
                    snapshotFile.exists().shouldBeTrue()
                }
            }
        }

        given("X-API-Version 헤더 파라미터") {
            `when`("문서의 각 오퍼레이션을 보면") {
                then("모든 오퍼레이션이 헤더를 받고, 버전을 선언한 매핑은 그 값이 기본값으로 채워진다") {
                    val document = objectMapper.readTree(
                        mockMvc.get("/v3/api-docs").andReturn().response.contentAsString,
                    )

                    fun versionHeaderOf(path: String, method: String) =
                        document.path("paths").path(path).path(method).path("parameters")
                            .firstOrNull { it.path("name").asText() == "X-API-Version" }

                    val operations = document.path("paths").properties()
                        .flatMap { (_, methods) -> methods.properties().map { it.value } }
                    operations.forEach { operation ->
                        operation.path("parameters").any { it.path("name").asText() == "X-API-Version" }
                            .shouldBeTrue()
                    }

                    versionHeaderOf("/api/reviews", "get")!!.path("schema").path("default").asText() shouldBe "1.0"
                }
            }

            `when`("헤더 필수 여부를 보면") {
                then("/api 오퍼레이션은 필수, 예외인 app-version 조회는 선택이다") {
                    val document = objectMapper.readTree(
                        mockMvc.get("/v3/api-docs").andReturn().response.contentAsString,
                    )

                    fun versionHeaderOf(path: String, method: String) =
                        document.path("paths").path(path).path(method).path("parameters")
                            .first { it.path("name").asText() == "X-API-Version" }

                    versionHeaderOf("/api/reviews", "get").path("required").asBoolean().shouldBeTrue()
                    versionHeaderOf("/api/app-version", "get").path("required").asBoolean().shouldBeFalse()
                }
            }
        }

        given("클라이언트 버전 헤더 파라미터") {
            `when`("문서의 각 오퍼레이션을 보면") {
                then("관리자 외 전 오퍼레이션은 선택 헤더 둘을 받고 관리자 오퍼레이션은 받지 않는다") {
                    val document = docOf("/v3/api-docs")
                    val clientVersionHeaders = listOf("X-OS-Version", "X-App-Version")

                    fun paramsOf(operation: com.fasterxml.jackson.databind.JsonNode) =
                        operation.path("parameters").filter { it.path("name").asText() in clientVersionHeaders }

                    val (adminOps, clientOps) = document.path("paths").properties()
                        .flatMap { (path, methods) -> methods.properties().map { path to it.value } }
                        .partition { (path, _) -> path.startsWith("/api/admin") || path.startsWith("/admin") }

                    adminOps.isNotEmpty().shouldBeTrue()
                    clientOps.isNotEmpty().shouldBeTrue()
                    adminOps.forEach { (_, operation) -> paramsOf(operation).size shouldBe 0 }
                    clientOps.forEach { (_, operation) ->
                        val params = paramsOf(operation)
                        params.map { it.path("name").asText() }.shouldContainAll(clientVersionHeaders)
                        params.forEach { it.path("required").asBoolean().shouldBeFalse() }
                    }
                }
            }
        }

        given("버전 그룹 문서") {
            `when`("매핑에 선언된 버전을 모으면") {
                then("모든 선언 버전이 그룹으로 노출된다 — 새 버전 추가 시 OpenApiConfig 에 그룹 빈을 함께 추가해야 한다") {
                    val declared = handlerMappings.flatMap { it.handlerMethods.keys }
                        .mapNotNull { info -> info.versionCondition.version?.removeSuffix("+") }
                        .toSet()

                    groupedOpenApis.map { it.group }.shouldContainAll(declared)
                }
            }

            `when`("/v3/api-docs/1.0 을 조회하면") {
                then("온보딩은 종전 계약 오퍼레이션 하나만 실리고 헤더 파라미터도 하나다") {
                    val onboarding = docOf("/v3/api-docs/1.0")
                        .path("paths").path("/api/members/me/onboarding").path("post")

                    onboarding.path("operationId").asText() shouldBe "completeOnboarding"
                    versionParamsOf(onboarding).size shouldBe 1
                    versionParamsOf(onboarding).single().path("schema").path("default").asText() shouldBe "1.0"
                }
            }

            `when`("/v3/api-docs/1.1 을 조회하면") {
                then("온보딩은 서버 자동 지정 계약이, 프로필 수정은 국적 제외 계약이 실리고 스캔은 v1 계약이 실린다") {
                    val document = docOf("/v3/api-docs/1.1")
                    val onboarding = document.path("paths").path("/api/members/me/onboarding").path("post")
                    val profilePatch = document.path("paths").path("/api/members/me/profile").path("patch")

                    onboarding.path("operationId").asText() shouldBe "completeOnboardingWithServerProfile"
                    versionParamsOf(onboarding).size shouldBe 1
                    versionParamsOf(onboarding).single().path("schema").path("default").asText() shouldBe "1.1"
                    versionParamsOf(profilePatch).single().path("schema").path("default").asText() shouldBe "1.1"
                    document.path("paths").path("/api/scans").path("post")
                        .path("operationId").asText() shouldBe "scan"
                }
            }

            `when`("/v3/api-docs/2.0 을 조회하면") {
                then("스캔 2.0 이 실리고 온보딩은 1.1+ 계약이 유지되며 전 오퍼레이션의 헤더 파라미터는 하나씩이다") {
                    val document = docOf("/v3/api-docs/2.0")

                    document.path("paths").has("/api/scans").shouldBeTrue()
                    document.path("paths").path("/api/members/me/onboarding").path("post")
                        .path("operationId").asText() shouldBe "completeOnboardingWithServerProfile"

                    document.path("paths").properties()
                        .flatMap { (_, methods) -> methods.properties().map { it.value } }
                        .forEach { operation -> versionParamsOf(operation).size shouldBe 1 }
                }
            }
        }

        given("에러 코드 섹션") {
            `when`("문서 설명(info.description)을 보면") {
                then("전 에러 코드가 표로 실리고 버전 그룹 문서에도 나온다") {
                    val fullDescription = docOf("/v3/api-docs").path("info").path("description").asText()
                    ErrorCode.entries.forEach { errorCode ->
                        fullDescription.contains(errorCode.code).shouldBeTrue()
                    }
                    docOf("/v3/api-docs/1.0").path("info").path("description").asText()
                        .contains("AUTH-004").shouldBeTrue()
                }
            }

            `when`("@ApiErrors 를 단 엔드포인트를 보면") {
                then("그 오퍼레이션 설명에 발생 가능한 에러 코드 표가 붙는다") {
                    val scanDesc = docOf("/v3/api-docs/1.0")
                        .path("paths").path("/api/scans").path("post").path("description").asText()
                    scanDesc.contains("발생 가능한 에러 코드").shouldBeTrue()
                    scanDesc.contains("SCAN-002").shouldBeTrue()
                    scanDesc.contains("SCAN-006").shouldBeTrue()
                }
            }
        }
    }
}
