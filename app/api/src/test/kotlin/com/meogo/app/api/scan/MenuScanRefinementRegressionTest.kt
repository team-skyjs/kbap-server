package com.meogo.app.api.scan

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
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

        fun seedReadyFood(koreanName: String): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO food (korean_name, description, spiciness, name_translations, description_translations,
                                      content_status, status, created_at, updated_at)
                    VALUES (?, '설명', 0, '{}', '{}', 'READY', 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE content_status = 'READY'
                    """,
                ).use { ps -> ps.setString(1, koreanName); ps.executeUpdate() }
            }

        fun countFood(koreanName: String): Int =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT COUNT(*) FROM food WHERE korean_name = ?").use { ps ->
                    ps.setString(1, koreanName)
                    ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                }
            }

        fun item(itemId: Int, name: String) = mapOf(
            "itemId" to itemId,
            "rawMenuName" to name,
            "boundingBox" to mapOf("x" to 0.1, "y" to 0.1, "width" to 0.3, "height" to 0.1),
        )

        given("실측 로그 회귀 — 진짜 메뉴 6종 + 메뉴판/잡음 혼합(폴백 경로)") {
            `when`("6종 메뉴는 완성 상태로 저장돼 있고 잡음이 섞여 들어오면") {
                then("6종은 MATCHED, '메뉴판'은 PENDING, 비한글 잡음은 NOT_FOOD 이며 food 를 오염시키지 않는다") {
                    val menus = listOf("회귀김치찌개", "회귀된장찌개", "회귀순두부찌개", "회귀부대찌개", "회귀고추장찌개", "회귀닭볶음탕")
                    menus.forEach(::seedReadyFood)

                    val items = listOf(
                        item(0, "회귀김치찌개 kimchi jjigae"),
                        item(1, "회귀된장찌개 doenjang"),
                        item(2, "회귀순두부찌개"),
                        item(3, "회귀부대찌개 budae"),
                        item(4, "회귀고추장찌개"),
                        item(5, "회귀닭볶음탕 dak"),
                        item(6, "메뉴판"),
                        item(7, "6,500"),
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
                        jsonPath("$.payload.results[6].riskLevel") { value("UNKNOWN") }
                        jsonPath("$.payload.results[7].matchStatus") { value("NOT_FOOD") }
                    }

                    countFood("메뉴판") shouldBe 0
                }
            }
        }
    }
}
