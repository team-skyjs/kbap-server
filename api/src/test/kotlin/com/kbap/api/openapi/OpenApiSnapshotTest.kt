package com.kbap.api.openapi

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class OpenApiSnapshotTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    private val objectMapper = jacksonObjectMapper()

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
    }
}
