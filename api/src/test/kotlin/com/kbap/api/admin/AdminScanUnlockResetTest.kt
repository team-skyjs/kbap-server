package com.kbap.api.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class AdminScanUnlockResetTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var memberRepository: MemberJpaRepository

    init {
        val mapper = jacksonObjectMapper()

        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun count(sql: String): Long = dataSource.connection.use { c ->
            c.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
        }

        fun unlockedMember(scanCount: Int, adRewards: Int, scans: Int = 0): Member {
            val member = memberRepository.save(Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "reset-${System.nanoTime()}", null)))
            execute("UPDATE member SET scan_unlocked = 1, scan_count = $scanCount WHERE id = ${member.id}")
            repeat(adRewards) { i ->
                execute(
                    "INSERT INTO member_ad_reward (member_id, reward_key, ad_unit_id, status, created_at, updated_at) " +
                        "VALUES (${member.id}, 'reset-${member.id}-$i', 'ca-app-pub-3940256099942544/5224354917', 'ACTIVE', NOW(6), NOW(6))",
                )
            }
            repeat(scans) { execute("INSERT INTO scan_history (member_id, status, created_at, updated_at) VALUES (${member.id}, 'ACTIVE', NOW(6), NOW(6))") }
            return member
        }

        fun reset(memberId: Long, role: MemberRole? = MemberRole.ADMIN): MockHttpServletResponse =
            mockMvc.post("/api/admin/members/$memberId/scan-unlock/reset") {
                if (role != null) header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, role)}")
                header("X-API-Version", "1.0")
            }.andReturn().response

        fun detail(memberId: Long): JsonNode = mapper.readTree(
            mockMvc.get("/api/admin/members/$memberId") {
                header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                header("X-API-Version", "1.0")
            }.andReturn().response.contentAsString,
        ).path("payload")

        fun payload(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.contentAsString).path("payload")
        fun code(response: MockHttpServletResponse): String = mapper.readTree(response.contentAsString).path("code").asText()

        beforeEach { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("관리자 스캔 무제한 해제 취소 — POST /api/admin/members/{id}/scan-unlock/reset") {
            `when`("광고 3회로 해제된 회원(스캔 1회)을 취소하면") {
                then("무제한 해제·광고 적립 0·스캔 수는 무료 한도로 — 남은 무료 0, 스캔 이력은 그대로, 관리자 상세에도 반영") {
                    val member = unlockedMember(scanCount = 1, adRewards = 3, scans = 2)
                    detail(member.id).let {
                        it.path("scanUnlocked").asBoolean() shouldBe true
                        it.path("adRewardCount").asInt() shouldBe 3
                    }

                    val response = reset(member.id)

                    response.status shouldBe 200
                    payload(response).path("scanUnlocked").asBoolean() shouldBe false
                    payload(response).path("adRewardCount").asInt() shouldBe 0
                    payload(response).path("scanCount").asInt() shouldBe Member.FREE_SCAN_LIMIT
                    payload(response).path("scanRemaining").asInt() shouldBe 0
                    count("SELECT scan_unlocked FROM member WHERE id = ${member.id}") shouldBe 0
                    count("SELECT COUNT(*) FROM member_ad_reward WHERE member_id = ${member.id} AND status = 'ACTIVE'") shouldBe 0
                    count("SELECT COUNT(*) FROM scan_history WHERE member_id = ${member.id}") shouldBe 2
                    detail(member.id).let {
                        it.path("scanUnlocked").asBoolean() shouldBe false
                        it.path("adRewardCount").asInt() shouldBe 0
                        it.path("scanCount").asInt() shouldBe Member.FREE_SCAN_LIMIT
                    }
                }
            }

            `when`("같은 회원을 두 번 취소하면") {
                then("두 번째도 200 이고 같은 상태다(멱등)") {
                    val member = unlockedMember(scanCount = 0, adRewards = 2)
                    reset(member.id).status shouldBe 200

                    val again = reset(member.id)

                    again.status shouldBe 200
                    payload(again).path("scanUnlocked").asBoolean() shouldBe false
                    payload(again).path("adRewardCount").asInt() shouldBe 0
                    payload(again).path("scanCount").asInt() shouldBe Member.FREE_SCAN_LIMIT
                }
            }

            `when`("스캔 수가 이미 무료 한도보다 크면") {
                then("줄이지 않는다 — 랭킹 점수의 스캔 수를 깎지 않는다") {
                    val member = unlockedMember(scanCount = 7, adRewards = 0)

                    payload(reset(member.id)).path("scanCount").asInt() shouldBe 7
                    count("SELECT scan_count FROM member WHERE id = ${member.id}") shouldBe 7
                }
            }

            `when`("봇 계정이면") {
                then("400 COMMON-002, 상태 그대로") {
                    val member = unlockedMember(scanCount = 0, adRewards = 0)
                    execute("UPDATE member SET is_bot = 1 WHERE id = ${member.id}")

                    val response = reset(member.id)

                    response.status shouldBe 400
                    code(response) shouldBe "COMMON-002"
                    count("SELECT scan_unlocked FROM member WHERE id = ${member.id}") shouldBe 1
                }
            }

            `when`("탈퇴한 회원이면") {
                then("400 MEMBER-003") {
                    val member = unlockedMember(scanCount = 0, adRewards = 0)
                    execute("UPDATE member SET status = 'DELETED' WHERE id = ${member.id}")

                    val response = reset(member.id)

                    response.status shouldBe 400
                    code(response) shouldBe "MEMBER-003"
                }
            }

            `when`("관리자가 아닌 토큰이거나 토큰이 없으면") {
                then("403 AUTH-008 / 401") {
                    val member = unlockedMember(scanCount = 0, adRewards = 1)

                    reset(member.id, MemberRole.USER).status shouldBe 403
                    reset(member.id, null).status shouldBe 401
                    count("SELECT scan_unlocked FROM member WHERE id = ${member.id}") shouldBe 1
                }
            }
        }
    }
}
