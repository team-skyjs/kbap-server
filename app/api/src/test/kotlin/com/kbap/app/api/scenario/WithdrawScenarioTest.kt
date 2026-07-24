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
            ScenarioFoodSeed.ensureFood(dataSource, "시나리오탈퇴비빔밥", spiciness = 2, substances = mapOf("EGG" to 100))
            val 사용자 = ScenarioApiDriver(mockMvc, "withdraw")
            var 탈퇴전리프레시토큰 = ""

            `when`("탈퇴한 뒤 이전 토큰으로 접근하고 같은 소셜 계정으로 재가입하면") {
                then("가입·온보딩·북마크를 거쳐 활동 이력을 만든다") {
                    사용자.회원가입한다() shouldBe true
                    사용자.온보딩한다(avoidanceSubstanceCodes = listOf("EGG")) shouldBe 200

                    사용자.음식을_검색한다("시나리오탈퇴비빔밥")
                    사용자.foodId shouldBeGreaterThan 0L
                    사용자.북마크한다() shouldBe 200

                    탈퇴전리프레시토큰 = 사용자.refreshToken
                }
                then("탈퇴 후 이전 액세스토큰은 MEMBER-003으로 거절된다") {
                    사용자.탈퇴한다() shouldBe 200

                    val 프로필응답 = 사용자.프로필을_조회한다()
                    프로필응답.상태코드 shouldBe 400
                    프로필응답.code shouldBe "MEMBER-003"
                }
                then("탈퇴 전 리프레시토큰은 AUTH-005로 거절된다") {
                    val 갱신응답 = 사용자.구_리프레시토큰으로_갱신을_시도한다(탈퇴전리프레시토큰)
                    갱신응답.상태코드 shouldBe 401
                    갱신응답.code shouldBe "AUTH-005"
                }
                then("같은 계정으로 재로그인하면 신규 회원으로 인증되며 이전 북마크는 없다") {
                    사용자.재로그인한다() shouldBe true
                    사용자.북마크_목록을_조회한다().size() shouldBe 0
                }
            }
        }
    }
}
