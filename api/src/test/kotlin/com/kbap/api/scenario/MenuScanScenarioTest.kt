package com.kbap.api.scenario

import com.kbap.api.image.FakeStorageObjectStore
import com.kbap.api.scan.FakeMenuBoardVisionExtractor
import com.kbap.common.port.llm.ExtractedMenu
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.core.testsupport.RedisContainerConfig
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
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
class MenuScanScenarioTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var storage: FakeStorageObjectStore

    @Autowired
    private lateinit var vision: FakeMenuBoardVisionExtractor

    init {
        given("수록된 음식이 있는 메뉴판을 촬영한 가입 사용자") {
            val 시드음식Id = ScenarioFoodSeed.ensureFood(dataSource, "시나리오스캔국밥", spiciness = 2, substances = emptyMap())
            val 사용자 = ScenarioApiDriver(mockMvc, "menuscan")

            `when`("업로드 URL 발급·업로드 완료·스캔을 차례로 수행하면") {
                then("가입과 온보딩으로 여정을 시작한다") {
                    사용자.회원가입한다() shouldBe true
                    사용자.온보딩한다() shouldBe 200
                }
                then("업로드 URL 발급 후 업로드 완료로 이어진다") {
                    사용자.업로드URL을_발급받는다("image/jpeg", 1024)
                    사용자.objectKey.shouldNotBeBlank()

                    storage.stub(사용자.objectKey, "image/jpeg", 1024)
                    사용자.업로드를_완료한다("image/jpeg", 1024) shouldBe 200
                }
                then("스캔 결과로 매칭·위험도가 반환된다") {
                    vision.program(
                        사용자.objectKey,
                        listOf(ExtractedMenu("Scenario Gukbap 시나리오스캔국밥", "시나리오스캔국밥", 9000, matchedIdx = 0)),
                    )

                    val 결과 = 사용자.스캔한다("시나리오스캔국밥")
                    결과.size() shouldBe 1
                    결과[0].path("matched").asBoolean() shouldBe true
                    결과[0].path("foodId").asLong() shouldBe 시드음식Id
                    결과[0].path("riskLevel").asText() shouldBe "SAFE"
                }
                then("홈의 최근 스캔에 그 음식이 노출된다") {
                    val 홈 = 사용자.홈을_조회한다()
                    홈.path("recentScans").map { it.path("foodId").asLong() } shouldContain 시드음식Id
                }
            }
        }
    }
}
