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
        given("대두가 든 음식이 수록된 서비스에 처음 방문한 사용자") {
            `when`("가입하고 대두 기피로 온보딩한 뒤 그 음식을 검색해 북마크하면") {
                then("홈·검색·상세·북마크 목록이 여정의 각 단계를 이어서 반영한다") {
                    ScenarioFoodSeed.ensureFood(dataSource, "시나리오된장찌개", spiciness = 3, substances = mapOf("SOY" to 100))
                    val 여정 = ScenarioApiDriver(mockMvc, "happy")

                    여정.회원가입한다() shouldBe true

                    여정.온보딩한다(avoidanceSubstanceCodes = listOf("SOY")) shouldBe 200

                    val 홈 = 여정.홈을_조회한다()
                    홈.path("authenticated").asBoolean() shouldBe true
                    홈.path("avoidedSubstances").map { it.path("code").asText() } shouldContain "SOY"

                    val 검색결과 = 여정.음식을_검색한다("시나리오된장찌개")
                    검색결과.size() shouldBeGreaterThan 0
                    여정.foodId shouldBeGreaterThan 0L

                    val 상세 = 여정.음식_상세를_조회한다()
                    상세.path("overallRiskStatus").asText() shouldBe "DANGER"
                    상세.path("bookmarked").asBoolean() shouldBe false

                    여정.북마크한다() shouldBe 200

                    val 북마크목록 = 여정.북마크_목록을_조회한다()
                    북마크목록.map { it.path("foodId").asLong() } shouldContain 여정.foodId
                }
            }
        }
    }
}
