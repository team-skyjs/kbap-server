package com.kbap.api.order

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class OrderItemLocalizedNameTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer

    init {
        val mapper = jacksonObjectMapper()

        fun execute(sql: String, vararg args: Any?) = dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
                ps.executeUpdate()
            }
        }

        fun seedFood(koreanName: String, nameTranslations: String, contentStatus: String): Long {
            execute(
                """
                INSERT INTO food (korean_name, description, spiciness, name_translations, description_translations,
                                  ingredients, content_status, status, created_at, updated_at)
                VALUES (?, '설명', 0, ?, '{}', '[]', ?, 'ACTIVE', NOW(6), NOW(6))
                """,
                koreanName, nameTranslations, contentStatus,
            )
            return dataSource.connection.use { c ->
                c.prepareStatement("SELECT id FROM food WHERE korean_name = ?").use { ps ->
                    ps.setString(1, koreanName)
                    ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                }
            }
        }

        fun detail(memberId: Long, orderId: Long, acceptLanguage: String?): JsonNode =
            mapper.readTree(
                mockMvc.get("/api/orders/$orderId") {
                    header("X-API-Version", "1.0")
                    header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(memberId, MemberRole.USER)}")
                    acceptLanguage?.let { header("Accept-Language", it) }
                }.andReturn().response.getContentAsString(Charsets.UTF_8),
            ).path("payload")

        fun namesOf(payload: JsonNode): List<String?> =
            payload.path("items").map { if (it.path("name").isNull) null else it.path("name").asText() }

        beforeEach { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("주문 상세 항목의 사용자 언어 음식 이름 — GET /api/orders/{id}") {
            `when`("공개 음식(번역 있음·일본어 번역 없음)과 준비중 음식을 담은 주문을 언어별로 조회하면") {
                then("공개 음식은 요청 언어 이름(번역 없으면 음식 API 처럼 카탈로그 한국어), 준비중 음식은 null — menuName 은 그대로") {
                    val memberId = 9_782L
                    execute(
                        """
                        INSERT INTO member (id, provider, provider_uid, member_status, onboarding_completed, status, created_at, updated_at)
                        VALUES (?, 'GOOGLE', 'kb782', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                        """,
                        memberId,
                    )
                    execute(
                        """
                        INSERT INTO uploaded_image (member_id, object_path, content_type, size_bytes, status, created_at, updated_at)
                        VALUES (?, 'kb782/menu.jpg', 'image/jpeg', 1024, 'ACTIVE', NOW(6), NOW(6))
                        """,
                        memberId,
                    )
                    val translated = seedFood("들깨칼국수", """{"en":"Perilla Seed Kalguksu","ja":"エゴマカルグクス"}""", "READY")
                    val englishOnly = seedFood("잔치국수", """{"en":"Banquet Noodles"}""", "READY")
                    val pending = seedFood("이상한국수", "{}", "PENDING_REVIEW")
                    val body = """{"imagePath":"kb782/menu.jpg","items":[""" +
                        listOf(translated to "들깨 칼국수", englishOnly to "잔치국수", pending to "이상한국수")
                            .joinToString(",") { (id, menu) -> """{"menuName":"$menu","quantity":1,"price":1000,"foodId":$id}""" } +
                        "]}"
                    val orderId = mapper.readTree(
                        mockMvc.post("/api/orders") {
                            header("X-API-Version", "1.0")
                            header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(memberId, MemberRole.USER)}")
                            contentType = MediaType.APPLICATION_JSON
                            content = body
                        }.andReturn().response.getContentAsString(Charsets.UTF_8),
                    ).path("payload").path("orderId").asLong()

                    val en = detail(memberId, orderId, "en")
                    namesOf(en) shouldBe listOf("Perilla Seed Kalguksu", "Banquet Noodles", null)
                    en.path("items").map { it.path("menuName").asText() } shouldBe listOf("들깨 칼국수", "잔치국수", "이상한국수")

                    namesOf(detail(memberId, orderId, "ja")) shouldBe listOf("エゴマカルグクス", "잔치국수", null)
                    namesOf(detail(memberId, orderId, "en-US,en;q=0.9")) shouldBe listOf(null, null, null)
                    namesOf(detail(memberId, orderId, null)) shouldBe listOf(null, null, null)
                }
            }
        }
    }
}
