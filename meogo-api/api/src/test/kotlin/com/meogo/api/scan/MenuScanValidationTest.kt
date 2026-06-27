package com.meogo.api.scan

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
class MenuScanValidationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    private fun box(x: Double = 0.1, y: Double = 0.1, width: Double = 0.3, height: Double = 0.1) =
        mapOf("x" to x, "y" to y, "width" to width, "height" to height)

    private fun item(
        itemId: Any? = 0,
        rawMenuName: Any? = "메뉴",
        boundingBox: Any? = box(),
    ): Map<String, Any?> = buildMap {
        put("itemId", itemId)
        put("rawMenuName", rawMenuName)
        put("boundingBox", boundingBox)
    }

    private fun expectBadRequest(payload: Map<String, Any?>) {
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

    @Test
    fun `빈 items 는 400`() = expectBadRequest(mapOf("items" to emptyList<Any>()))

    @Test
    fun `101개 항목은 400`() {
        val items = (0..100).map { item(itemId = it) }
        expectBadRequest(mapOf("items" to items))
    }

    @Test
    fun `itemId 중복은 400`() =
        expectBadRequest(mapOf("items" to listOf(item(itemId = 1), item(itemId = 1))))

    @Test
    fun `rawMenuName blank 는 400`() =
        expectBadRequest(mapOf("items" to listOf(item(rawMenuName = "  "))))

    @Test
    fun `boundingBox 누락은 400`() =
        expectBadRequest(mapOf("items" to listOf(item(boundingBox = null))))

    @Test
    fun `width 0 은 400`() =
        expectBadRequest(mapOf("items" to listOf(item(boundingBox = box(width = 0.0)))))

    @Test
    fun `x 음수는 400`() =
        expectBadRequest(mapOf("items" to listOf(item(boundingBox = box(x = -1.0)))))

    @Test
    fun `x+width 가 1 초과면 400`() =
        expectBadRequest(mapOf("items" to listOf(item(boundingBox = box(x = 0.8, width = 0.5)))))
}
