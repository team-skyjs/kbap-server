package com.kbap.api.admin

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.SpicinessPreference
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.Cookie
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminMemberPageControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var memberJpaRepository: MemberJpaRepository

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    init {
        fun adminCookie(): Cookie =
            Cookie(AdminPageAuthInterceptor.COOKIE_NAME, tokenIssuer.issueAccessToken(1, MemberRole.ADMIN))

        fun clearMembers() {
            dataSource.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM member_block") } }
            memberJpaRepository.deleteAll()
        }

        fun saveMember(uid: String, nickname: String? = null, profileImageUrl: String? = null): Member =
            memberJpaRepository.save(
                Member(
                    provider = SocialProvider.GOOGLE,
                    providerUid = uid,
                    email = "$uid@test.com",
                    nickname = nickname,
                    profileImageUrl = profileImageUrl,
                ),
            )

        fun memberPageOf(result: MvcResult): AdminMemberPageView =
            result.modelAndView!!.model["memberPage"] as AdminMemberPageView

        fun getMembers(query: String = ""): MvcResult =
            mockMvc.get("/admin/members$query") { cookie(adminCookie()) }
                .andExpect {
                    status { isOk() }
                    view { name("admin/members") }
                }.andReturn()

        beforeContainer { clearMembers() }

        given("회원 목록 페이징") {
            `when`("회원 21명에서 첫 페이지를 조회하면") {
                then("id 내림차순 20건과 페이지 정보를 내려준다") {
                    val ids = (1..21).map { saveMember("page-uid-$it").id }

                    val page = memberPageOf(getMembers())

                    page.items.size shouldBe 20
                    page.items.first().id shouldBe ids.max()
                    page.page shouldBe 1
                    page.totalPages shouldBe 2
                    page.totalCount shouldBe 21
                    page.hasPrev shouldBe false
                    page.hasNext shouldBe true
                }
            }

            `when`("2페이지를 조회하면") {
                then("남은 1건과 이전 페이지 존재를 내려준다") {
                    (1..21).map { saveMember("page2-uid-$it") }

                    val page = memberPageOf(getMembers("?page=2"))

                    page.items.size shouldBe 1
                    page.page shouldBe 2
                    page.hasPrev shouldBe true
                    page.hasNext shouldBe false
                }
            }

            `when`("회원이 없으면") {
                then("오류 없이 빈 목록을 내려준다") {
                    val page = memberPageOf(getMembers())

                    page.items shouldBe emptyList()
                    page.totalCount shouldBe 0
                }
            }

            `when`("범위 초과·음수·비숫자 page 로 조회하면") {
                then("오류 없이 각각 빈 목록 또는 1페이지로 보정한다") {
                    saveMember("bound-uid-1")

                    memberPageOf(getMembers("?page=99")).items shouldBe emptyList()
                    memberPageOf(getMembers("?page=-3")).page shouldBe 1
                    memberPageOf(getMembers("?page=abc")).page shouldBe 1
                }
            }

            `when`("정지·탈퇴 회원이 섞여 있으면") {
                then("정지(SUSPENDED)는 상태와 함께 노출되고 탈퇴(소프트 삭제)는 제외된다") {
                    saveMember("status-active")
                    val suspended = saveMember("status-suspended")
                    suspended.memberStatus = MemberStatus.SUSPENDED
                    memberJpaRepository.save(suspended)
                    val withdrawn = saveMember("status-withdrawn")
                    withdrawn.delete()
                    memberJpaRepository.save(withdrawn)

                    val page = memberPageOf(getMembers())

                    page.totalCount shouldBe 2
                    page.items.first { it.id == suspended.id }.memberStatus shouldBe MemberStatus.SUSPENDED
                    page.items.none { it.id == withdrawn.id } shouldBe true
                }
            }
        }

        given("회원 상세") {
            `when`("존재하는 회원을 조회하면") {
                then("프로필(이미지는 공개 URL)과 상태를 내려준다") {
                    val saved = saveMember("detail-uid", nickname = "상세닉네임", profileImageUrl = "profile/1.jpg")
                        .apply { spicinessPreference = SpicinessPreference.EXTREME }
                        .let { memberJpaRepository.save(it) }

                    val result = mockMvc.get("/admin/members/${saved.id}") { cookie(adminCookie()) }
                        .andExpect {
                            status { isOk() }
                            view { name("admin/member-detail") }
                        }.andReturn()

                    val detail = result.modelAndView!!.model["member"] as AdminMemberDetailView
                    detail.shouldNotBeNull()
                    detail.id shouldBe saved.id
                    detail.nickname shouldBe "상세닉네임"
                    detail.provider shouldBe SocialProvider.GOOGLE
                    detail.memberStatus shouldBe MemberStatus.ACTIVE
                    detail.profileImageUrl shouldBe "https://cdn.test/profile/1.jpg"
                    detail.spicinessPreference shouldBe "EXTREME"
                }
            }

            `when`("존재하지 않는 id 로 조회하면") {
                then("오류 대신 안내 화면을 보여준다") {
                    val result = mockMvc.get("/admin/members/999999") { cookie(adminCookie()) }
                        .andExpect {
                            status { isOk() }
                            view { name("admin/member-detail") }
                        }.andReturn()

                    result.modelAndView!!.model["member"] shouldBe null
                }
            }
        }

        given("회원 화면 접근 제어") {
            `when`("미인증으로 목록에 접근하면") {
                then("로그인 화면으로 리다이렉트한다") {
                    mockMvc.get("/admin/members").andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/login")
                    }
                }
            }
        }
    }
}
