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

            `when`("DANGER 가 조밀해 스캔 상한 안에 페이지가 차면") {
                then("hasNext 페이지는 꽉 차고(20개) 커서로 끝까지 이어진다") {
                    val token = eggMember(6005L)
                    val dangerIds = (100L..140L).toList() // 41개 연속 DANGER
                    dangerIds.forEach { seedFood(it, 90) }

                    val collected = mutableListOf<Long>()
                    var cursor: Long? = null
                    var guard = 0
                    while (true) {
                        guard++ shouldBeLessThan 10
                        val payload = mapper.readTree(
                            browse(token, risk = "DANGER", cursor = cursor).andReturn().response.getContentAsString(Charsets.UTF_8),
                        ).path("payload")
                        collected += payload.path("items").map { it.path("foodId").asLong() }
                        if (!payload.path("hasNext").asBoolean()) { payload.path("nextCursor").isNull shouldBe true; break }
                        val next = payload.path("nextCursor").asLong()
                        cursor?.let { next shouldBeLessThan it }
                        cursor = next
                    }
                    collected shouldContainExactlyInAnyOrder dangerIds
                }
            }

            `when`("게스트가 risk=DANGER 로 조회하면 (회피성분 없음 → 도달 불가)") {
                then("DB 접근 없이 즉시 빈 페이지·hasNext=false 를 반환한다") {
                    // 회피성분 없는 회원(=게스트 위험도 판정과 동일: 겹침 0)으로 검증
                    val plain = tokenIssuer.issueAccessToken(6006L, MemberRole.USER)
                    dataSource.connection.use { c ->
                        c.prepareStatement(
                            "INSERT INTO member (id, provider, provider_uid, member_status, onboarding_completed, status, created_at, updated_at) " +
                                "VALUES (6006, 'GOOGLE', 'risk-plain', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6)) ON DUPLICATE KEY UPDATE id = id",
                        ).use { it.executeUpdate() }
                    }
                    seedFood(9001L, 90) // DANGER for egg-avoiders, but this viewer avoids nothing

                    val payload = mapper.readTree(
                        browse(plain, risk = "DANGER,CAUTION").andReturn().response.getContentAsString(Charsets.UTF_8),
                    ).path("payload")
                    payload.path("items").size() shouldBe 0
                    payload.path("hasNext").asBoolean() shouldBe false
                    payload.path("nextCursor").isNull shouldBe true
                }
            }

            `when`("매치가 스캔 상한(500행) 밖에 흩어져 있으면") {
                then("얇은/빈 페이지에 hasNext=true 로 이어받아 끝까지 드레인되고 전 매치를 모은다") {
                    val token = eggMember(6007L)
                    // SAFE 600개(상한 500행 초과) 뒤(더 작은 id)에 DANGER 3개 — 첫 요청은 상한 도달로 빈 얇은 페이지
                    val dangerIds = listOf(700L, 701L, 702L)
                    dangerIds.forEach { seedFood(it, 90) }
                    (1000L..1599L).forEach { seedFood(it, null) } // 600 SAFE, id 가 더 커서 먼저 스캔됨

                    val collected = mutableListOf<Long>()
                    var cursor: Long? = null
                    var sawThinPage = false
                    var guard = 0
                    while (true) {
                        guard++ shouldBeLessThan 60
                        val payload = mapper.readTree(
                            browse(token, risk = "DANGER", cursor = cursor).andReturn().response.getContentAsString(Charsets.UTF_8),
                        ).path("payload")
                        val pageIds = payload.path("items").map { it.path("foodId").asLong() }
                        collected += pageIds
                        val hasNext = payload.path("hasNext").asBoolean()
                        if (hasNext && pageIds.size < 20) sawThinPage = true
                        if (!hasNext) { payload.path("nextCursor").isNull shouldBe true; break }
                        val next = payload.path("nextCursor").asLong()
                        cursor?.let { next shouldBeLessThan it }
                        cursor = next
                    }
                    sawThinPage shouldBe true
                    collected shouldContainExactlyInAnyOrder dangerIds
                }
            }
        }
    }
}
