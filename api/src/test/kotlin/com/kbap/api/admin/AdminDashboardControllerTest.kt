package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.admin.AdminTestTokens.adminHeaders
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
class AdminDashboardControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var contentOutboxRepository: FoodContentOutboxJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    private val objectMapper = jacksonObjectMapper()

    init {
        fun get(query: String = ""): MvcResult =
            mockMvc.get("/api/admin/dashboard$query") { adminHeaders(AdminTestTokens.adminAccessToken(tokenIssuer)) }.andReturn()

        @Suppress("UNCHECKED_CAST")
        fun payload(result: MvcResult): Map<String, Any?> =
            objectMapper.readValue<Map<String, Any?>>(result.response.contentAsString)["payload"] as Map<String, Any?>

        beforeContainer {
            AdminTestTables.clear(dataSource, "food_content_outbox", "food_vector_outbox", "food")
        }

        afterSpec {
            AdminTestTables.clear(dataSource, "food_content_outbox", "food_vector_outbox", "food")
        }

        given("GET /api/admin/dashboard") {
            `when`("기본 조회하면") {
                then("7일 배열·상태 라벨·비용 범위 문구·KRW·고착 카운트가 오고 화면 전용 필드는 없다") {
                    val food = foodRepository.save(Food(koreanName = "대시보드음식", description = "설명", contentStatus = FoodContentStatus.READY))
                    val stuck = FoodContentOutbox.pending(food.id, "대시보드음식").apply { markSent() }
                    contentOutboxRepository.save(stuck)
                    AdminTestTables.ageSentAt(dataSource, stuck.id, 4)

                    val result = get()
                    result.response.status shouldBe 200
                    val p = payload(result)

                    @Suppress("UNCHECKED_CAST")
                    val foods = p["foods"] as Map<String, Any?>
                    foods["total"] shouldBe 1
                    @Suppress("UNCHECKED_CAST")
                    (foods["byStatus"] as List<Map<String, Any?>>).single { it["code"] == "READY" }["label"] shouldBe "준비 완료"

                    @Suppress("UNCHECKED_CAST")
                    val outbox = p["contentOutbox"] as Map<String, Any?>
                    outbox["stuckCount"] shouldBe 1
                    outbox["stuckHours"] shouldBe 3
                    outbox["canceled"] shouldBe 0
                    @Suppress("UNCHECKED_CAST")
                    val stuckRows = outbox["stuck"] as List<Map<String, Any?>>
                    stuckRows.single()["outboxId"] shouldBe stuck.id.toInt()
                    stuckRows.single()["displayName"] shouldBe "대시보드음식"

                    @Suppress("UNCHECKED_CAST")
                    val vector = p["vectorOutbox"] as Map<String, Any?>
                    (vector["failures"] as List<*>).size shouldBe 0

                    @Suppress("UNCHECKED_CAST")
                    val generating = p["generatingPreview"] as List<Map<String, Any?>>
                    generating.single()["status"] shouldBe "SENT"
                    generating.single()["foodId"] shouldBe food.id.toInt()

                    @Suppress("UNCHECKED_CAST")
                    val metrics = p["metrics"] as Map<String, Any?>
                    metrics["days"] shouldBe 7
                    (metrics["dailyScans"] as List<*>).size shouldBe 7
                    @Suppress("UNCHECKED_CAST")
                    val llm = metrics["llmCost"] as Map<String, Any?>
                    (llm["scopeNote"] as String) shouldContain "임베딩"
                    @Suppress("UNCHECKED_CAST")
                    val day = (llm["daily"] as List<Map<String, Any?>>).first()
                    day.containsKey("costKrw") shouldBe true
                    day.containsKey("heightPct") shouldBe false
                    day.containsKey("dayLabel") shouldBe false
                }
            }

            `when`("검수·이미지 대기 음식이 있으면") {
                then("캐러셀 미리보기에 최근 수정순으로 최대 14건이 온다") {
                    repeat(15) { foodRepository.save(Food(koreanName = "검수대기$it", description = "설명", contentStatus = FoodContentStatus.PENDING_REVIEW)) }
                    val image = foodRepository.save(Food(koreanName = "이미지대기", description = "설명", contentStatus = FoodContentStatus.PENDING_IMAGE))

                    val p = payload(get())
                    @Suppress("UNCHECKED_CAST")
                    val review = p["pendingReviewPreview"] as List<Map<String, Any?>>
                    review.size shouldBe 14
                    review.first()["koreanName"] shouldBe "검수대기14"
                    review.first().containsKey("imageUrl") shouldBe true
                    @Suppress("UNCHECKED_CAST")
                    val pendingImage = p["pendingImagePreview"] as List<Map<String, Any?>>
                    pendingImage.single()["id"] shouldBe image.id.toInt()
                }
            }

            `when`("days=30 으로 조회하면") {
                then("30일 배열이 온다") {
                    @Suppress("UNCHECKED_CAST")
                    val metrics = payload(get("?days=30"))["metrics"] as Map<String, Any?>
                    metrics["days"] shouldBe 30
                    (metrics["dailyNewFoods"] as List<*>).size shouldBe 30
                }
            }

            `when`("days 가 범위 밖이면") {
                then("400") {
                    get("?days=0").response.status shouldBe 400
                    get("?days=91").response.status shouldBe 400
                }
            }
        }
    }
}
