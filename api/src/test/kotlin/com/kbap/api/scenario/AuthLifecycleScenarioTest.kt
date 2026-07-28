package com.kbap.api.scenario

import com.kbap.common.application.auth.token.AuthTokenProperties
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.core.testsupport.RedisContainerConfig
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class, RedisContainerConfig::class, ScenarioSocialTokenVerifierConfig::class)
@Tags("scenario")
class AuthLifecycleScenarioTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var authTokenProperties: AuthTokenProperties

    init {
        given("가입 직후 액세스 토큰이 만료된 사용자") {
            val 사용자 = ScenarioApiDriver(mockMvc, "auth-lifecycle", authTokenProperties)
            var 갱신직전_리프레시토큰 = ""

            `when`("만료·갱신에 따른 직전 토큰 무효화·로그아웃을 거쳐 재로그인하면") {
                then("만료된 액세스토큰은 AUTH-004로 거절된다") {
                    사용자.회원가입한다() shouldBe true

                    val 만료응답 = 사용자.만료된_액세스토큰으로_프로필을_조회한다()
                    만료응답.상태코드 shouldBe 401
                    만료응답.code shouldBe "AUTH-004"
                }
                then("리프레시로 갱신하면 새 토큰으로 프로필을 볼 수 있다") {
                    갱신직전_리프레시토큰 = 사용자.토큰을_갱신한다()
                    사용자.프로필을_조회한다().상태코드 shouldBe 200
                }
                then("갱신 전에 사용하던 리프레시토큰은 AUTH-005로 거절된다") {
                    val 갱신전_토큰거절 = 사용자.구_리프레시토큰으로_갱신을_시도한다(갱신직전_리프레시토큰)
                    갱신전_토큰거절.상태코드 shouldBe 401
                    갱신전_토큰거절.code shouldBe "AUTH-005"
                }
                then("로그아웃한 뒤 리프레시토큰을 사용하면 AUTH-005로 거절된다") {
                    val 로그아웃직전_리프레시토큰 = 사용자.refreshToken
                    사용자.로그아웃한다() shouldBe 200

                    val 로그아웃후_토큰거절 = 사용자.구_리프레시토큰으로_갱신을_시도한다(로그아웃직전_리프레시토큰)
                    로그아웃후_토큰거절.상태코드 shouldBe 401
                    로그아웃후_토큰거절.code shouldBe "AUTH-005"
                }
                then("같은 소셜 계정 재로그인은 기존 회원으로 인증된다") {
                    사용자.재로그인한다() shouldBe false
                }
            }
        }
    }
}
