package com.kbap.api.food

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
class FoodRiskFilterControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        fun eggMember(memberId: Long): String {
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO member (id, provider, provider_uid, avoidance_substance_codes, member_status,
                                        onboarding_completed, status, created_at, updated_at)
                    VALUES (?, 'GOOGLE', ?, '["EGG"]', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE avoidance_substance_codes = VALUES(avoidance_substance_codes)
                    """,
                ).use { ps -> ps.setLong(1, memberId); ps.setString(2, "risk-$memberId"); ps.executeUpdate() }
            }
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        // inclusion_percent: >=60 DANGER, 10~59 CAUTION, <10 SAFE. EGG 미포함/[] → 겹침 없음 → SAFE.
        fun seedFood(id: Long, eggPercent: Int?) {
            val ingredients = eggPercent?.let { """[{"code":"EGG","inclusion_percent":$it}]""" } ?: "[]"
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO food (id, korean_name, description, spiciness, name_translations,
                                      description_translations, ingredients, content_status, status, created_at, updated_at)
                    VALUES (?, ?, '설명', 0, '{}', '{}', CAST(? AS JSON), 'READY', 'ACTIVE', NOW(6), NOW(6))
                    """,
                ).use { ps -> ps.setLong(1, id); ps.setString(2, "위험도음식$id"); ps.setString(3, ingredients); ps.executeUpdate() }
            }
        }

        fun browse(token: String, risk: String? = null, cursor: Long? = null) =
            mockMvc.get("/api/foods") {
                header("Authorization", "Bearer $token")
                param("lang", "ko")
                risk?.let { param("risk", it) }
                cursor?.let { param("cursor", it.toString()) }
            }

        fun idsOf(json: String): List<Long> =
            mapper.readTree(json).path("payload").path("items").map { it.path("foodId").asLong() }

        given("GET /api/foods?risk — 위험도 서버 필터") {
            `when`("risk=DANGER 로 조회하면") {
                then("조회자 기준 DANGER 인 음식만 내려온다") {
                    val token = eggMember(6001L)
                    seedFood(1L, 80)  // DANGER
                    seedFood(2L, 30)  // CAUTION
                    seedFood(3L, null) // SAFE(겹침 없음)

                    val ids = idsOf(browse(token, risk = "DANGER").andReturn().response.getContentAsString(Charsets.UTF_8))
                    ids shouldContainExactlyInAnyOrder listOf(1L)
                }
            }

            `when`("risk=DANGER,CAUTION 로 조회하면") {
                then("두 위험도 음식만 내려온다(OR)") {
                    val token = eggMember(6002L)
                    seedFood(11L, 80)
                    seedFood(12L, 30)
                    seedFood(13L, null)

                    val ids = idsOf(browse(token, risk = "DANGER,CAUTION").andReturn().response.getContentAsString(Charsets.UTF_8))
                    ids shouldContainExactlyInAnyOrder listOf(11L, 12L)
                }
            }

            `when`("risk 미지정이면") {
                then("현행대로 전체가 내려온다") {
                    val token = eggMember(6003L)
                    seedFood(21L, 80)
                    seedFood(22L, null)

                    val ids = idsOf(browse(token).andReturn().response.getContentAsString(Charsets.UTF_8))
                    ids shouldContainExactlyInAnyOrder listOf(21L, 22L)
                }
            }

            `when`("미정의 risk 값이면") {
                then("400 COMMON-002 로 거절한다") {
                    val token = eggMember(6004L)
                    browse(token, risk = "SCARY").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("DANGER 음식이 페이지 크기를 넘고 SAFE 가 섞여 있으면") {
                then("필터 집합 기준으로 꽉 찬 페이지·정상 커서로 빈/얇은 페이지 없이 끝까지 이어진다") {
                    val token = eggMember(6005L)
                    // 41개 DANGER + 사이사이 SAFE 를 섞어 필터가 얇은 페이지를 만들지 않는지 검증
                    var id = 100L
                    val dangerIds = mutableListOf<Long>()
                    repeat(41) {
                        seedFood(id, 90); dangerIds += id; id++
                        seedFood(id, null); id++  // SAFE 잡음
                    }

                    val collected = mutableListOf<Long>()
                    var cursor: Long? = null
                    var guard = 0
                    while (true) {
                        guard++ shouldBeLessThan 10
                        val payload = mapper.readTree(
                            browse(token, risk = "DANGER", cursor = cursor).andReturn().response.getContentAsString(Charsets.UTF_8),
                        ).path("payload")
                        val pageIds = payload.path("items").map { it.path("foodId").asLong() }
                        collected += pageIds
                        val hasNext = payload.path("hasNext").asBoolean()
                        if (hasNext) pageIds.size shouldBe 20 // 필터돼도 꽉 찬 페이지
                        if (!hasNext) { payload.path("nextCursor").isNull shouldBe true; break }
                        val next = payload.path("nextCursor").asLong()
                        cursor?.let { next shouldBeLessThan it }
                        cursor = next
                    }
                    collected shouldContainExactlyInAnyOrder dangerIds
                }
            }
        }
    }
}
