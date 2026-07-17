package com.kbap.app.api.scenario

import com.kbap.core.testsupport.MySqlContainerConfig
import com.kbap.core.testsupport.RedisContainerConfig
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class, RedisContainerConfig::class, ScenarioSocialTokenVerifierConfig::class)
@Tags("scenario")
class WithdrawScenarioTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        given("활동 이력(온보딩·북마크)이 있는 가입 회원") {
            `when`("탈퇴한 뒤 구 토큰으로 접근하고 같은 소셜 계정으로 재가입하면") {
                then("구 토큰은 전부 거절되고 신규 회원으로 시작하며 이전 활동이 노출되지 않는다") {
                    ScenarioFoodSeed.ensureFood(dataSource, "시나리오탈퇴비빔밥", spiciness = 2, substances = mapOf("EGG" to 100))
                    val 여정 = ScenarioApiDriver(mockMvc, "withdraw")

                    여정.회원가입한다() shouldBe true
                    여정.온보딩한다(avoidanceSubstanceCodes = listOf("EGG")) shouldBe 200

                    여정.음식을_검색한다("시나리오탈퇴비빔밥")
                    여정.foodId shouldBeGreaterThan 0L
                    여정.북마크한다() shouldBe 200

                    val 탈퇴전리프레시토큰 = 여정.refreshToken
                    여정.탈퇴한다() shouldBe 200

                    val 프로필응답 = 여정.프로필을_조회한다()
                    프로필응답.상태코드 shouldBe 400
                    프로필응답.code shouldBe "MEMBER-003"

                    val 갱신응답 = 여정.구_리프레시토큰으로_갱신을_시도한다(탈퇴전리프레시토큰)
                    갱신응답.상태코드 shouldBe 401
                    갱신응답.code shouldBe "AUTH-005"

                    여정.재로그인한다() shouldBe true

                    여정.북마크_목록을_조회한다().size() shouldBe 0
                }
            }
        }
    }
}
