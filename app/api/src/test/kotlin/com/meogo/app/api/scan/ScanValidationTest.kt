package com.meogo.app.api.scan

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class ScanValidationTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    init {
        val objectMapper = jacksonObjectMapper()

        fun item(
            idx: Any? = 0,
            rawMenuName: Any? = "메뉴",
        ): Map<String, Any?> = buildMap {
            put("idx", idx)
            put("rawMenuName", rawMenuName)
        }

        suspend fun expectBadRequest(payload: Map<String, Any?>) {
            mockMvc.post("/api/v1/scans") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(payload)
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.success") { value(false) }
                jsonPath("$.payload") { doesNotExist() }
                jsonPath("$.message") { exists() }
            }
        }

        given("메뉴 스캔 제출 요청 검증") {
            `when`("items 가 비어 있으면") {
                then("400 을 반환한다") { expectBadRequest(mapOf("items" to emptyList<Any>())) }
            }

            `when`("항목이 101개이면") {
                then("400 을 반환한다") {
                    expectBadRequest(mapOf("items" to (0..100).map { item(idx = it) }))
                }
            }

            `when`("idx 가 중복이면") {
                then("400 을 반환한다") {
                    expectBadRequest(mapOf("items" to listOf(item(idx = 1), item(idx = 1))))
                }
            }

            `when`("idx 가 누락되면") {
                then("400 을 반환한다") {
                    expectBadRequest(mapOf("items" to listOf(item(idx = null))))
                }
            }

            `when`("rawMenuName 이 blank 이면") {
                then("400 을 반환한다") {
                    expectBadRequest(mapOf("items" to listOf(item(rawMenuName = "  "))))
                }
            }
        }
    }
}
