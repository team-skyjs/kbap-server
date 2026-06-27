package com.meogo.api.presentation.scan

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
class MenuScanValidationTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    init {
        val objectMapper = jacksonObjectMapper()

        fun box(x: Double = 0.1, y: Double = 0.1, width: Double = 0.3, height: Double = 0.1) =
            mapOf("x" to x, "y" to y, "width" to width, "height" to height)

        fun item(
            itemId: Any? = 0,
            rawMenuName: Any? = "메뉴",
            boundingBox: Any? = box(),
        ): Map<String, Any?> = buildMap {
            put("itemId", itemId)
            put("rawMenuName", rawMenuName)
            put("boundingBox", boundingBox)
        }

        suspend fun expectBadRequest(payload: Map<String, Any?>) {
            mockMvc.post("/api/v1/menu-scans") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(payload)
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.success") { value(false) }
                jsonPath("$.data") { doesNotExist() }
                jsonPath("$.message") { exists() }
            }
        }

        given("메뉴 스캔 제출 요청 검증") {
            `when`("items 가 비어 있으면") {
                then("400 을 반환한다") { expectBadRequest(mapOf("items" to emptyList<Any>())) }
            }

            `when`("항목이 101개이면") {
                then("400 을 반환한다") {
                    expectBadRequest(mapOf("items" to (0..100).map { item(itemId = it) }))
                }
            }

            `when`("itemId 가 중복이면") {
                then("400 을 반환한다") {
                    expectBadRequest(mapOf("items" to listOf(item(itemId = 1), item(itemId = 1))))
                }
            }

            `when`("rawMenuName 이 blank 이면") {
                then("400 을 반환한다") {
                    expectBadRequest(mapOf("items" to listOf(item(rawMenuName = "  "))))
                }
            }

            `when`("boundingBox 가 누락되면") {
                then("400 을 반환한다") {
                    expectBadRequest(mapOf("items" to listOf(item(boundingBox = null))))
                }
            }

            `when`("boundingBox.width 가 0 이면") {
                then("400 을 반환한다") {
                    expectBadRequest(mapOf("items" to listOf(item(boundingBox = box(width = 0.0)))))
                }
            }

            `when`("boundingBox.x 가 음수이면") {
                then("400 을 반환한다") {
                    expectBadRequest(mapOf("items" to listOf(item(boundingBox = box(x = -1.0)))))
                }
            }

            `when`("boundingBox 의 x + width 가 1 을 초과하면") {
                then("400 을 반환한다") {
                    expectBadRequest(mapOf("items" to listOf(item(boundingBox = box(x = 0.8, width = 0.5)))))
                }
            }
        }
    }
}
