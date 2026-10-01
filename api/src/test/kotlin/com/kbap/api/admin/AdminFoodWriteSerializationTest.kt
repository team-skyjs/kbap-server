package com.kbap.api.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@IntegrationTest
class AdminFoodWriteSerializationTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var foodRepository: FoodJpaRepository
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var reviewService: AdminFoodContentReviewService
    @Autowired private lateinit var foodService: AdminFoodService

    private val mapper = jacksonObjectMapper()

    init {
        fun token(): String = tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)

        fun saveFood(name: String, status: FoodContentStatus): Food =
            foodRepository.save(Food(koreanName = name, description = "설명", imageRef = "images/webp/$name.webp", contentStatus = status))

        fun bodyOf(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.getContentAsString(Charsets.UTF_8))

        fun whileAnotherAdminHolds(write: () -> Unit, request: () -> MockHttpServletResponse): MockHttpServletResponse {
            val written = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val other = executor.submit {
                TransactionTemplate(transactionManager).execute {
                    write()
                    written.countDown()
                    Thread.sleep(1_500)
                }
            }
            executor.shutdown()
            if (!written.await(30, TimeUnit.SECONDS)) other.get(1, TimeUnit.SECONDS)
            val response = request()
            other.get(30, TimeUnit.SECONDS)
            return response
        }

        beforeEach { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("다른 관리자의 쓰기가 커밋되기 전에 같은 음식에 들어온 어드민 쓰기") {
            `when`("검수 승인이 진행 중일 때 같은 음식을 다시 승인하면") {
                then("앞 요청 뒤로 직렬화돼 이미 공개된 상태를 보고 멱등 200 이다 — 낙관적 락 충돌(COMMON-004)이 나지 않는다") {
                    val food = saveFood("직렬화승인음식", FoodContentStatus.PENDING_REVIEW)

                    val response = whileAnotherAdminHolds({ reviewService.applyContentReviewResult(food.id, true, null) }) {
                        mockMvc.post("/api/admin/foods/content-reviews/${food.id}") {
                            header("Authorization", "Bearer ${token()}")
                            contentType = MediaType.APPLICATION_JSON
                            content = """{"passed":true}"""
                        }.andReturn().response
                    }

                    response.status shouldBe 200
                    foodRepository.findById(food.id).orElseThrow().contentStatus shouldBe FoodContentStatus.READY
                }
            }

            `when`("검수 승인이 진행 중일 때 같은 음식을 반려하면") {
                then("승인된 상태를 보고 400 FOOD-008(검수 대상 아님)이다 — 승인을 덮어쓰지도, COMMON-004 로 끝나지도 않는다") {
                    val food = saveFood("직렬화반려음식", FoodContentStatus.PENDING_REVIEW)

                    val response = whileAnotherAdminHolds({ reviewService.applyContentReviewResult(food.id, true, null) }) {
                        mockMvc.post("/api/admin/foods/content-reviews/${food.id}") {
                            header("Authorization", "Bearer ${token()}")
                            contentType = MediaType.APPLICATION_JSON
                            content = """{"passed":false,"reason":"늦은 반려"}"""
                        }.andReturn().response
                    }

                    response.status shouldBe 400
                    bodyOf(response).path("code").asText() shouldBe "FOOD-008"
                    foodRepository.findById(food.id).orElseThrow().contentStatus shouldBe FoodContentStatus.READY
                }
            }

            `when`("삭제가 진행 중일 때 같은 음식을 다시 삭제하면") {
                then("삭제된 상태를 보고 FOOD-001 이다 — COMMON-004 가 나지 않는다") {
                    val food = saveFood("직렬화삭제음식", FoodContentStatus.READY)

                    val response = whileAnotherAdminHolds({ foodService.deleteFood(food.id) }) {
                        mockMvc.delete("/api/admin/foods/${food.id}") { header("Authorization", "Bearer ${token()}") }.andReturn().response
                    }

                    bodyOf(response).path("code").asText() shouldBe "FOOD-001"
                }
            }

            `when`("복원이 진행 중일 때 같은 음식을 다시 복원하면") {
                then("복원된 상태를 보고 200 restored=false 다 — COMMON-004 가 나지 않는다") {
                    val food = saveFood("직렬화복원음식", FoodContentStatus.READY)
                    foodService.deleteFood(food.id)

                    val response = whileAnotherAdminHolds({ foodService.restoreFood(food.id) }) {
                        mockMvc.post("/api/admin/foods/${food.id}/restore") { header("Authorization", "Bearer ${token()}") }.andReturn().response
                    }

                    response.status shouldBe 200
                    bodyOf(response).path("payload").path("restored").asBoolean() shouldBe false
                    foodRepository.findById(food.id).orElseThrow().koreanName shouldBe "직렬화복원음식"
                }
            }
        }
    }
}
