package com.kbap.api.member

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.auth.FakeSocialTokenVerifier
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class AdRewardControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val objectMapper = jacksonObjectMapper()

        fun loginAccessToken(sub: String = FakeSocialTokenVerifier.DEFAULT_SUB): String {
            val response = mockMvc.post("/api/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("idToken" to sub))
            }.andReturn().response
            return objectMapper.readTree(response.contentAsString).path("payload").path("accessToken").asText()
        }

        fun claim(token: String?, rewardKey: String?, adUnitId: String? = "ca-app-pub-1/2") =
            mockMvc.post("/api/members/me/ad-rewards") {
                if (token != null) header("Authorization", "Bearer $token")
                header("X-API-Version", "1.0")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("rewardKey" to rewardKey, "adUnitId" to adUnitId))
            }.andReturn().response

        fun getMyProfile(token: String): JsonNode =
            objectMapper.readTree(
                mockMvc.get("/api/members/me/profile") {
                    header("Authorization", "Bearer $token")
                    header("X-API-Version", "1.0")
                }.andReturn().response.contentAsString,
            ).path("payload")

        fun withdraw(token: String) =
            mockMvc.patch("/api/auth/withdraw") {
                header("Authorization", "Bearer $token")
                header("X-API-Version", "1.0")
            }.andReturn().response

        fun payload(response: MockHttpServletResponse): JsonNode = objectMapper.readTree(response.contentAsString).path("payload")
        fun code(response: MockHttpServletResponse): String = objectMapper.readTree(response.contentAsString).path("code").asText()

        fun rewardRows(): Int =
            dataSource.connection.use { c ->
                c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM member_ad_reward").use { rs -> rs.next(); rs.getInt(1) } }
            }

        fun memberFlag(providerUid: String, column: String): Int =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT $column FROM member WHERE provider_uid = ?").use { ps ->
                    ps.setString(1, providerUid)
                    ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                }
            }

        fun updateMember(providerUid: String, assignment: String) {
            dataSource.connection.use { c ->
                c.prepareStatement("UPDATE member SET $assignment WHERE provider_uid = ?").use { ps ->
                    ps.setString(1, providerUid)
                    ps.executeUpdate()
                }
            }
        }

        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("보상형 광고 적립 POST /api/members/me/ad-rewards") {
            `when`("회원이 첫 보상을 신고하면") {
                then("200 — 적립 1, 해제까지 2, 아직 미해제, 이력 1행") {
                    val token = loginAccessToken()
                    val response = claim(token, "reward-1")

                    response.status shouldBe 200
                    payload(response).path("rewardCount").asInt() shouldBe 1
                    payload(response).path("remainingForUnlock").asInt() shouldBe 2
                    payload(response).path("scanUnlocked").asBoolean() shouldBe false
                    rewardRows() shouldBe 1
                    memberFlag(FakeSocialTokenVerifier.DEFAULT_SUB, "scan_unlocked") shouldBe 0
                }
            }

            `when`("같은 rewardKey 를 두 번 보내면") {
                then("두 번째도 200 이지만 적립은 1 그대로다(멱등)") {
                    val token = loginAccessToken()
                    claim(token, "reward-dup").status shouldBe 200

                    val second = claim(token, "reward-dup")

                    second.status shouldBe 200
                    payload(second).path("rewardCount").asInt() shouldBe 1
                    payload(second).path("remainingForUnlock").asInt() shouldBe 2
                    rewardRows() shouldBe 1
                }
            }

            `when`("다른 회원이 이미 쓰인 rewardKey 를 보내면") {
                then("400 COMMON-002 로 거절하고 적립하지 않는다") {
                    val mine = loginAccessToken()
                    val other = loginAccessToken("ad-reward-other")
                    claim(mine, "reward-shared").status shouldBe 200

                    val response = claim(other, "reward-shared")

                    response.status shouldBe 400
                    code(response) shouldBe "COMMON-002"
                    rewardRows() shouldBe 1
                }
            }

            `when`("서로 다른 키로 3회 누적하면") {
                then("세 번째 응답에서 scanUnlocked=true 가 되고 프로필도 무제한(scanRemaining=null)이다") {
                    val token = loginAccessToken()
                    payload(claim(token, "reward-a")).path("scanUnlocked").asBoolean() shouldBe false
                    payload(claim(token, "reward-b")).path("scanUnlocked").asBoolean() shouldBe false

                    val third = claim(token, "reward-c")

                    third.status shouldBe 200
                    payload(third).path("rewardCount").asInt() shouldBe 3
                    payload(third).path("remainingForUnlock").asInt() shouldBe 0
                    payload(third).path("scanUnlocked").asBoolean() shouldBe true
                    memberFlag(FakeSocialTokenVerifier.DEFAULT_SUB, "scan_unlocked") shouldBe 1
                    val profile = getMyProfile(token)
                    profile.path("scanUnlocked").asBoolean() shouldBe true
                    profile.path("scanRemaining").isNull shouldBe true
                    profile.path("adRewardCount").asInt() shouldBe 3
                }
            }

            `when`("이미 해제된 회원이 신고하면") {
                then("적립만 늘고 해제 상태는 그대로 true 다") {
                    val token = loginAccessToken()
                    updateMember(FakeSocialTokenVerifier.DEFAULT_SUB, "scan_unlocked = 1")

                    val response = claim(token, "reward-after-unlock")

                    response.status shouldBe 200
                    payload(response).path("rewardCount").asInt() shouldBe 1
                    payload(response).path("remainingForUnlock").asInt() shouldBe 2
                    payload(response).path("scanUnlocked").asBoolean() shouldBe true
                    memberFlag(FakeSocialTokenVerifier.DEFAULT_SUB, "scan_unlocked") shouldBe 1
                }
            }

            `when`("탈퇴한 회원이 남은 토큰으로 신고하면") {
                then("400 MEMBER-003, 이력 없음") {
                    val token = loginAccessToken()
                    withdraw(token).status shouldBe 200

                    val response = claim(token, "reward-withdrawn")

                    response.status shouldBe 400
                    code(response) shouldBe "MEMBER-003"
                    rewardRows() shouldBe 0
                }
            }

            `when`("정지된 회원이 신고하면") {
                then("400 MEMBER-003, 이력 없음") {
                    val token = loginAccessToken()
                    updateMember(FakeSocialTokenVerifier.DEFAULT_SUB, "member_status = 'SUSPENDED'")

                    val response = claim(token, "reward-suspended")

                    response.status shouldBe 400
                    code(response) shouldBe "MEMBER-003"
                    rewardRows() shouldBe 0
                }
            }

            `when`("최근 24시간에 상한(10회)만큼 적립한 뒤 또 신고하면") {
                then("429 MEMBER-014 로 거절하고 이력은 10행 그대로다 — 같은 키 재요청은 여전히 200") {
                    val token = loginAccessToken()
                    repeat(AdRewardService.DAILY_LIMIT) { claim(token, "reward-day-$it").status shouldBe 200 }

                    val over = claim(token, "reward-day-over")

                    over.status shouldBe 429
                    code(over) shouldBe "MEMBER-014"
                    rewardRows() shouldBe AdRewardService.DAILY_LIMIT
                    payload(claim(token, "reward-day-0")).path("rewardCount").asInt() shouldBe AdRewardService.DAILY_LIMIT
                }
            }

            `when`("rewardKey 가 비었거나 없으면") {
                then("400 COMMON-002") {
                    val token = loginAccessToken()
                    code(claim(token, "")) shouldBe "COMMON-002"
                    code(claim(token, null)) shouldBe "COMMON-002"
                    code(claim(token, "reward-no-unit", adUnitId = null)) shouldBe "COMMON-002"
                    rewardRows() shouldBe 0
                }
            }

            `when`("게스트(토큰 없음)가 신고하면") {
                then("401") {
                    claim(null, "reward-guest").status shouldBe 401
                }
            }
        }

        given("내 프로필의 광고 적립 필드") {
            `when`("적립 전후로 조회하면") {
                then("adRewardCount 0→2, adRewardsForUnlock 는 항상 3") {
                    val token = loginAccessToken()
                    getMyProfile(token).let {
                        it.path("adRewardCount").asInt() shouldBe 0
                        it.path("adRewardsForUnlock").asInt() shouldBe 3
                    }
                    claim(token, "reward-p1")
                    claim(token, "reward-p2")

                    getMyProfile(token).let {
                        it.path("adRewardCount").asInt() shouldBe 2
                        it.path("adRewardsForUnlock").asInt() shouldBe 3
                        it.path("scanUnlocked").asBoolean() shouldBe false
                    }
                }
            }
        }
    }
}
