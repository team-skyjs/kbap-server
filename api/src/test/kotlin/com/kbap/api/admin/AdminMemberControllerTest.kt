package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.admin.AdminTestTokens.adminHeaders
import com.kbap.api.auth.FakeSocialAccountDeleter
import com.kbap.api.auth.FakeSocialTokenVerifierConfig
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.admin.AdminAuditLogJpaRepository
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.MemberRankingEventJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import com.kbap.common.domain.scan.model.ScanHistory
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class, FakeSocialTokenVerifierConfig::class)
class AdminMemberControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var memberRepository: MemberJpaRepository

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var scanHistoryRepository: ScanHistoryJpaRepository

    @Autowired
    private lateinit var rankingEventRepository: MemberRankingEventJpaRepository

    @Autowired
    private lateinit var auditLogRepository: AdminAuditLogJpaRepository

    @Autowired
    private lateinit var accountDeleter: FakeSocialAccountDeleter

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    private val objectMapper = jacksonObjectMapper()

    init {
        fun seed(
            uid: String,
            nickname: String? = uid,
            provider: SocialProvider = SocialProvider.GOOGLE,
            onboarding: Boolean = true,
            email: String? = "$uid@test.com",
        ): Member = memberRepository.save(
            Member(provider = provider, providerUid = uid, email = email, nickname = nickname, onboardingCompleted = onboarding),
        )

        fun token() = AdminTestTokens.adminAccessToken(tokenIssuer)

        fun get(path: String): MvcResult = mockMvc.get("/api/admin/members$path") { adminHeaders(token()) }.andReturn()

        fun patch(path: String, body: String): MvcResult =
            mockMvc.patch("/api/admin/members$path") {
                adminHeaders(token())
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()

        fun json(r: MvcResult): Map<String, Any?> = objectMapper.readValue(r.response.contentAsString)

        @Suppress("UNCHECKED_CAST")
        fun payload(r: MvcResult) = json(r)["payload"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        fun items(r: MvcResult) = payload(r)["items"] as List<Map<String, Any?>>

        fun nicknames(r: MvcResult) = items(r).map { it["nickname"] }

        beforeContainer {
            dataSource.connection.use { c ->
                c.createStatement().use { st ->
                    listOf("member_ranking_event", "scan_history", "admin_audit_log", "food", "member").forEach { st.execute("DELETE FROM $it") }
                }
            }
            accountDeleter.reset()
        }

        given("GET /api/admin/members") {
            `when`("검색·필터·탈퇴 포함을 조합하면") {
                then("조건에 맞는 회원만 오고 이메일은 마스킹된다") {
                    val abc = seed("u1", nickname = "abc")
                    seed("u2", nickname = "abcd", provider = SocialProvider.APPLE)
                    seed("u3", nickname = "xyz", onboarding = false)
                    val gone = seed("u4", nickname = "gone").apply { withdraw() }.let { memberRepository.save(it) }
                    seed("u5", nickname = "sus").apply { suspend("욕설") }.let { memberRepository.save(it) }

                    nicknames(get("?q=abc")) shouldContainExactlyInAnyOrder listOf("abc", "abcd")
                    nicknames(get("?q=${abc.id}")) shouldContainExactly listOf("abc")
                    nicknames(get("?email=u3@")) shouldContainExactly listOf("xyz")
                    nicknames(get("?provider=APPLE")) shouldContainExactly listOf("abcd")
                    nicknames(get("?memberStatus=SUSPENDED")) shouldContainExactly listOf("sus")
                    nicknames(get("?onboardingCompleted=false")) shouldContainExactly listOf("xyz")
                    nicknames(get("")).size shouldBe 4
                    val withGone = get("?includeWithdrawn=true")
                    nicknames(withGone).size shouldBe 5
                    items(withGone).single { it["id"] == gone.id.toInt() }["withdrawn"] shouldBe true
                    items(get("?q=abc")).single { it["nickname"] == "abc" }["email"] shouldBe "u1***@test.com"
                    nicknames(get("?sort=nickname,asc")).first() shouldBe "abc"
                    get("?size=201").response.status shouldBe 400
                }
            }
        }

        given("GET /api/admin/members/{id}") {
            `when`("활동이 있는 회원을 조회하면") {
                then("providerUid 없이 마스킹된 이메일·스캔·랭킹·활동 집계가 온다") {
                    val member = seed("detail", nickname = "상세")
                    val food = foodRepository.save(Food(koreanName = "상세음식", description = "설명"))
                    repeat(4) { scanHistoryRepository.save(ScanHistory(memberId = member.id, price = null, foodId = food.id)) }

                    val p = payload(get("/${member.id}"))

                    p.containsKey("providerUid") shouldBe false
                    p["email"] shouldBe "de***@test.com"
                    @Suppress("UNCHECKED_CAST")
                    val scan = p["scan"] as Map<String, Any?>
                    scan["scanAllowed"] shouldBe true
                    @Suppress("UNCHECKED_CAST")
                    val ranking = p["ranking"] as Map<String, Any?>
                    ranking["tier"] shouldBe "NEWCOMER"
                    ranking.containsKey("pointsToNext") shouldBe true
                    @Suppress("UNCHECKED_CAST")
                    val activity = p["activity"] as Map<String, Any?>
                    activity["scanCount"] shouldBe 4
                    activity["reviewCount"] shouldBe 0
                    @Suppress("UNCHECKED_CAST")
                    val recentScans = activity["recentScans"] as List<Map<String, Any?>>
                    recentScans.size shouldBe 4
                    recentScans.first()["displayName"] shouldBe "상세음식"
                }
            }

            `when`("탈퇴 회원을 조회하면") {
                then("withdrawn:true 로 온다") {
                    val member = seed("gone2").apply { withdraw() }.let { memberRepository.save(it) }

                    payload(get("/${member.id}"))["withdrawn"] shouldBe true
                }
            }

            `when`("없는 회원이면") {
                then("400 MEMBER-003") {
                    val result = get("/999999")
                    result.response.status shouldBe 400
                    json(result)["code"] shouldBe "MEMBER-003"
                }
            }

            `when`("랭킹 원장을 조회하면") {
                then("페이지 메타와 함께 온다") {
                    val member = seed("rank")

                    val p = payload(get("/${member.id}/ranking-events?size=10"))
                    p["totalCount"] shouldBe 0
                    p["size"] shouldBe 10
                    (p["items"] as List<*>).size shouldBe 0
                }
            }
        }

        given("회원 조치") {
            `when`("사유 없이 정지하면") {
                then("400") {
                    val member = seed("nores")
                    patch("/${member.id}/status", """{"memberStatus":"SUSPENDED"}""").response.status shouldBe 400
                }
            }

            `when`("사유와 함께 정지하고 다시 활성화하면") {
                then("상태·사유·시각이 기록되고 감사가 남는다") {
                    val member = seed("suspend")

                    patch("/${member.id}/status", """{"memberStatus":"SUSPENDED","reason":"욕설 반복"}""").response.status shouldBe 200
                    memberRepository.findById(member.id).get().let {
                        it.memberStatus shouldBe MemberStatus.SUSPENDED
                        it.suspendReason shouldBe "욕설 반복"
                        (it.suspendedAt != null) shouldBe true
                    }
                    payload(get("/${member.id}"))["suspendReason"] shouldBe "욕설 반복"
                    auditLogRepository.findAll().single().action shouldBe AdminAuditAction.MEMBER_STATUS

                    patch("/${member.id}/status", """{"memberStatus":"SUSPENDED","reason":"재요청"}""").response.status shouldBe 200
                    auditLogRepository.count() shouldBe 1

                    patch("/${member.id}/status", """{"memberStatus":"ACTIVE"}""").response.status shouldBe 200
                    memberRepository.findById(member.id).get().let {
                        it.memberStatus shouldBe MemberStatus.ACTIVE
                        it.suspendedAt.shouldBeNull()
                        it.suspendReason.shouldBeNull()
                    }
                }
            }

            `when`("프로필을 초기화하면") {
                then("닉네임·이미지가 기본값이 되고 둘 다 false 면 400") {
                    val member = seed("prof", nickname = "욕설닉").apply { profileImageUrl = "profile/x.png" }.let { memberRepository.save(it) }

                    patch("/${member.id}/profile", """{"resetNickname":true,"resetProfileImage":true}""").response.status shouldBe 200
                    memberRepository.findById(member.id).get().let {
                        it.nickname shouldBe "사용자${member.id}"
                        it.profileImageUrl.shouldBeNull()
                    }
                    patch("/${member.id}/profile", """{}""").response.status shouldBe 400
                }
            }

            `when`("스캔 제한을 해제하면") {
                then("scanAllowed 가 true 가 된다") {
                    val member = seed("scan").apply { scanCount = 3 }.let { memberRepository.save(it) }
                    @Suppress("UNCHECKED_CAST")
                    (payload(get("/${member.id}"))["scan"] as Map<String, Any?>)["scanAllowed"] shouldBe false

                    mockMvc.post("/api/admin/members/${member.id}/scan-unlock") { adminHeaders(token()) }.andExpect { status { isOk() } }

                    @Suppress("UNCHECKED_CAST")
                    (payload(get("/${member.id}"))["scan"] as Map<String, Any?>)["scanAllowed"] shouldBe true
                }
            }

            `when`("강제 탈퇴하면") {
                then("소셜 삭제 후 탈퇴되고 재호출은 멱등, 소셜 삭제 실패는 500 + 감사") {
                    val member = seed("wd")

                    mockMvc.delete("/api/admin/members/${member.id}") { adminHeaders(token()) }.andExpect { status { isOk() } }
                    accountDeleter.deleted.single().second shouldBe "wd"
                    items(get("?includeWithdrawn=true&q=${member.id}")).single()["withdrawn"] shouldBe true
                    mockMvc.delete("/api/admin/members/${member.id}") { adminHeaders(token()) }.andExpect { status { isOk() } }

                    val other = seed("wd2")
                    accountDeleter.fail()
                    val result = mockMvc.delete("/api/admin/members/${other.id}") { adminHeaders(token()) }.andReturn()
                    result.response.status shouldBe 500
                    json(result)["code"] shouldBe "AUTH-007"
                    memberRepository.findById(other.id).isPresent shouldBe true
                    auditLogRepository.findAll().any { it.action == AdminAuditAction.MEMBER_WITHDRAW_FAILED && it.targetId == other.id } shouldBe true
                }
            }
        }
    }
}
