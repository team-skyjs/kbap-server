package com.kbap.app.api.scenario

import com.kbap.application.auth.token.AuthTokenProperties
import com.kbap.core.testsupport.MySqlContainerConfig
import com.kbap.core.testsupport.RedisContainerConfig
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
            `when`("토큰을 갱신해 이어가다 로그아웃 후 재로그인하면") {
                then("만료·갱신·회전·로그아웃 각 단계가 토큰 유효성을 올바르게 전이시킨다") {
                    val 여정 = ScenarioApiDriver(mockMvc, "auth-lifecycle", authTokenProperties)

                    여정.회원가입한다() shouldBe true

                    val 만료응답 = 여정.만료된_액세스토큰으로_프로필을_조회한다()
                    만료응답.상태코드 shouldBe 401
                    만료응답.code shouldBe "AUTH-004"

                    val 회전전_리프레시토큰 = 여정.토큰을_갱신한다()

                    여정.프로필을_조회한다().상태코드 shouldBe 200

                    val 회전거절 = 여정.구_리프레시토큰으로_갱신을_시도한다(회전전_리프레시토큰)
                    회전거절.상태코드 shouldBe 401
                    회전거절.code shouldBe "AUTH-005"

                    val 로그아웃시점_리프레시토큰 = 여정.refreshToken
                    여정.로그아웃한다() shouldBe 200

                    val 로그아웃후거절 = 여정.구_리프레시토큰으로_갱신을_시도한다(로그아웃시점_리프레시토큰)
                    로그아웃후거절.상태코드 shouldBe 401
                    로그아웃후거절.code shouldBe "AUTH-005"

                    여정.재로그인한다() shouldBe false
                }
            }
        }
    }
}
