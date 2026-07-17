package com.kbap.app.api.scenario

import com.kbap.core.testsupport.MySqlContainerConfig
import com.kbap.core.testsupport.RedisContainerConfig
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
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
class HappyPathScenarioTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        given("대두가 든 음식이 수록된 서비스를 처음 방문한 사용자") {
            ScenarioFoodSeed.ensureFood(dataSource, "시나리오된장찌개", spiciness = 3, substances = mapOf("SOY" to 100))
            val 사용자 = ScenarioApiDriver(mockMvc, "happy")

            `when`("가입부터 북마크까지 이어서 진행하면") {
                then("가입하면 신규 회원으로 인증된다") {
                    사용자.회원가입한다() shouldBe true
                }
                then("온보딩 결과가 인증 상태·기피 성분으로 홈에 반영된다") {
                    사용자.온보딩한다(avoidanceSubstanceCodes = listOf("SOY")) shouldBe 200

                    val 홈 = 사용자.홈을_조회한다()
                    홈.path("authenticated").asBoolean() shouldBe true
                    홈.path("avoidedSubstances").map { it.path("code").asText() } shouldContain "SOY"
                }
                then("검색·상세 조회에서 위험도가 이어서 반환된다") {
                    val 검색결과 = 사용자.음식을_검색한다("시나리오된장찌개")
                    검색결과.size() shouldBeGreaterThan 0
                    사용자.foodId shouldBeGreaterThan 0L

                    val 상세 = 사용자.음식_상세를_조회한다()
                    상세.path("overallRiskStatus").asText() shouldBe "DANGER"
                    상세.path("bookmarked").asBoolean() shouldBe false
                }
                then("북마크하면 그 음식이 목록에 담긴다") {
                    사용자.북마크한다() shouldBe 200
                    사용자.북마크_목록을_조회한다().map { it.path("foodId").asLong() } shouldContain 사용자.foodId
                }
            }
        }
    }
}
