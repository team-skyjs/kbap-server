package com.meogo.app.api.scan

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.meogo.application.client.auth.TokenIssuer
import com.meogo.domain.member.MemberRole
import com.meogo.core.testsupport.MySqlContainerConfig
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
class ScanRefinementRegressionTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    init {
        val objectMapper = jacksonObjectMapper()

        fun accessToken(): String {
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO member (id, provider, provider_uid, profile, member_status,
                                        onboarding_completed, status, created_at, updated_at)
                    VALUES (42, 'GOOGLE', 'scan-test-42', '{}', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE id = id
                    """,
                ).use { it.executeUpdate() }
            }
            return tokenIssuer.issueAccessToken(42L, MemberRole.USER)
        }

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

        fun item(idx: Int, name: String) = mapOf(
            "idx" to idx,
            "rawMenuName" to name,
        )

        given("실측 로그 회귀 — 진짜 메뉴 6종 + 메뉴판/잡음 혼합(폴백 경로)") {
            `when`("6종 메뉴는 완성 상태로 저장돼 있고 잡음이 섞여 들어오면") {
                then("6종은 matched=true, '메뉴판'은 matched=false, 비한글 잡음은 결과에서 제외되며 food 를 오염시키지 않는다") {
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

                    mockMvc.post("/api/v1/scans") {
                        header("Authorization", "Bearer ${accessToken()}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body
                    }.andExpect {
                        status { isOk() }
                        (0..5).forEach { i ->
                            jsonPath("$.payload.results[$i].matched") { value(true) }
                            jsonPath("$.payload.results[$i].foodId") { exists() }
                        }
                        jsonPath("$.payload.results[6].matched") { value(false) }
                        jsonPath("$.payload.results[6].riskLevel") { value("UNKNOWN") }
                        jsonPath("$.payload.results[6].idx") { value(6) }
                        jsonPath("$.payload.results.length()") { value(7) }
                    }

                    countFood("메뉴판") shouldBe 0
                }
            }
        }
    }
}
