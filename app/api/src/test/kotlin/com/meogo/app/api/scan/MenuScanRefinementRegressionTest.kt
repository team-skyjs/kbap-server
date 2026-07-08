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
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class MenuScanRefinementRegressionTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val objectMapper = jacksonObjectMapper()

        fun seedFood(koreanName: String): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO food (korean_name, description, spiciness, name_translations, description_translations,
                                      status, created_at, updated_at)
                    VALUES (?, '설명', 0, '{}', '{}', 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE status = 'ACTIVE'
                    """,
                ).use { ps -> ps.setString(1, koreanName); ps.executeUpdate() }
            }

        fun item(itemId: Int, name: String) = mapOf(
            "itemId" to itemId,
            "rawMenuName" to name,
            "boundingBox" to mapOf("x" to 0.1, "y" to 0.1, "width" to 0.3, "height" to 0.1),
        )

        given("실측 로그 회귀 — 진짜 메뉴 6종 + 메뉴판/잡음 혼합(폴백 경로)") {
            `when`("6종 메뉴는 저장돼 있고 잡음이 섞여 들어오면") {
                then("6종은 MATCHED, '메뉴판'·비음식 잡음은 매칭되지 않는다") {
                    val menus = listOf("김치찌개", "된장찌개", "순두부찌개", "부대찌개", "고추장찌개", "닭볶음탕")
                    menus.forEach(::seedFood)

                    val items = listOf(
                        item(0, "김치찌개 kimchi jjigae"),
                        item(1, "된장찌개 doenjang"),
                        item(2, "순두부찌개"),
                        item(3, "부대찌개 budae"),
                        item(4, "고추장찌개"),
                        item(5, "닭볶음탕 dak"),
                        item(6, "메뉴판"),
                        item(7, "6,500"),
                        item(8, "원산지 중국"),
                    )
                    val body = objectMapper.writeValueAsString(mapOf("items" to items))

                    mockMvc.post("/api/v1/menu-scans") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body
                    }.andExpect {
                        status { isOk() }
                        (0..5).forEach { i ->
                            jsonPath("$.payload.results[$i].matchStatus") { value("MATCHED") }
                            jsonPath("$.payload.results[$i].foodId") { exists() }
                        }
                        jsonPath("$.payload.results[6].matchStatus") { value("PENDING") }
                        jsonPath("$.payload.results[7].matchStatus") { value("NOT_FOOD") }
                    }
                }
            }
        }
    }
}
