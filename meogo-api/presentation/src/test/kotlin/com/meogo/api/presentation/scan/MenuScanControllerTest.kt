package com.meogo.api.presentation.scan

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
class MenuScanControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    private fun item(itemId: Int, name: String = "메뉴$itemId") = mapOf(
        "itemId" to itemId,
        "rawMenuName" to name,
        "boundingBox" to mapOf("x" to 0.1, "y" to 0.1, "width" to 0.3, "height" to 0.1),
    )

    private fun body(vararg items: Map<String, Any>) =
        objectMapper.writeValueAsString(mapOf("items" to items.toList()))

    @Test
    fun `4개 항목 제출하면 200 과 itemId 1대1 매칭 및 4단계 분포를 반환한다`() {
        mockMvc.post("/api/v1/menu-scans") {
            contentType = MediaType.APPLICATION_JSON
            content = body(item(0), item(1), item(2), item(3))
        }.andExpect {
            status { isOk() }
            jsonPath("$.success") { value(true) }
            jsonPath("$.data.scanId") { exists() }
            jsonPath("$.data.results.length()") { value(4) }
            jsonPath("$.data.results[0].itemId") { value(0) }
            jsonPath("$.data.results[0].riskLevel") { value("SAFE") }
            jsonPath("$.data.results[1].riskLevel") { value("CAUTION") }
            jsonPath("$.data.results[2].riskLevel") { value("DANGER") }
            jsonPath("$.data.results[3].riskLevel") { value("UNKNOWN") }
        }
    }

    @Test
    fun `같은 메뉴명이라도 서로 다른 itemId 로 구분 매칭된다`() {
        mockMvc.post("/api/v1/menu-scans") {
            contentType = MediaType.APPLICATION_JSON
            content = body(item(10, "된장찌개"), item(20, "된장찌개"))
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.results[0].itemId") { value(10) }
            jsonPath("$.data.results[1].itemId") { value(20) }
            jsonPath("$.data.results[0].riskLevel") { value("SAFE") }
            jsonPath("$.data.results[1].riskLevel") { value("CAUTION") }
        }
    }

    @Test
    fun `5번째 항목은 index 4 라 SAFE 로 재순환한다`() {
        mockMvc.post("/api/v1/menu-scans") {
            contentType = MediaType.APPLICATION_JSON
            content = body(item(0), item(1), item(2), item(3), item(4))
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.results[4].riskLevel") { value("SAFE") }
        }
    }
}
