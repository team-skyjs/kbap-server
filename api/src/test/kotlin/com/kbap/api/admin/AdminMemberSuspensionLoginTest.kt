package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.auth.FakeSocialTokenVerifier
import com.kbap.api.auth.FakeSocialTokenVerifierConfig
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.core.testsupport.RedisContainerConfig
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class, RedisContainerConfig::class, FakeSocialTokenVerifierConfig::class)
class AdminMemberSuspensionLoginTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var memberRepository: MemberJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val objectMapper = jacksonObjectMapper()

    init {
        fun socialLogin(): MvcResult =
            mockMvc.post("/api/auth/login") {
                header("X-API-Version", AdminTestTokens.API_VERSION)
                contentType = MediaType.APPLICATION_JSON
                content = """{"idToken":"any"}"""
            }.andReturn()

        fun code(r: MvcResult): String? = objectMapper.readValue<Map<String, Any?>>(r.response.contentAsString)["code"] as String?

        beforeContainer { memberRepository.deleteAll() }

        given("정지 회원") {
            `when`("소셜 로그인하면") {
                then("403 MEMBER-012 이고 중복 계정 오류(409)가 아니다") {
                    memberRepository.save(
                        Member(provider = SocialProvider.GOOGLE, providerUid = FakeSocialTokenVerifier.DEFAULT_SUB, email = "s@x.com", nickname = "정지")
                            .apply { suspend("정책 위반") },
                    )

                    val result = socialLogin()

                    result.response.status shouldBe 403
                    code(result) shouldBe "MEMBER-012"
                }
            }

            `when`("정지 전 발급된 액세스 토큰으로 회원 API 를 호출하면") {
                then("403 MEMBER-012") {
                    val member = memberRepository.save(
                        Member(provider = SocialProvider.GOOGLE, providerUid = "other-sub", nickname = "정지2").apply { suspend("정책 위반") },
                    )

                    val result = mockMvc.get("/api/members/me/profile") {
                        header("Authorization", "Bearer ${AdminTestTokens.userAccessToken(tokenIssuer, member.id)}")
                        header("X-API-Version", AdminTestTokens.API_VERSION)
                    }.andReturn()

                    result.response.status shouldBe 403
                    code(result) shouldBe "MEMBER-012"
                }
            }

            `when`("정지가 해제되면") {
                then("소셜 로그인이 정상 동작한다") {
                    val member = memberRepository.save(
                        Member(provider = SocialProvider.GOOGLE, providerUid = FakeSocialTokenVerifier.DEFAULT_SUB, nickname = "복귀").apply { suspend("정책 위반") },
                    )
                    memberRepository.save(member.apply { reinstate() })

                    socialLogin().response.status shouldBe 200
                }
            }
        }
    }
}
