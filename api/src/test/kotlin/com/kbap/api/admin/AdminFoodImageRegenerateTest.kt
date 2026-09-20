package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class AdminFoodImageRegenerateTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var itemRepository: ImageBatchItemJpaRepository

    @Autowired
    private lateinit var vectorOutboxRepository: FoodVectorOutboxJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        fun clear(): Unit =
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute("DELETE FROM food_vector_outbox")
                    it.execute("DELETE FROM image_batch_item")
                    it.execute("DELETE FROM image_batch")
                    it.execute("DELETE FROM food_image")
                    it.execute("DELETE FROM food")
                }
            }

        beforeContainer { clear() }
        afterSpec { clear() }

        fun adminToken(): String = tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)

        fun saveFood(name: String, status: FoodContentStatus = FoodContentStatus.READY): Food =
            foodRepository.save(
                Food(koreanName = name, description = "설명", imageRef = "images/webp/$name.webp", contentStatus = status),
            )

        fun regenerate(foodId: Long, token: String? = adminToken()): ResultActionsDsl =
            mockMvc.post("/api/admin/foods/$foodId/regenerate-image") {
                token?.let { header("Authorization", "Bearer $it") }
            }

        fun submit(body: Map<String, Any?>? = null, token: String? = adminToken()): ResultActionsDsl =
            mockMvc.post("/api/admin/foods/images") {
                token?.let { header("Authorization", "Bearer $it") }
                if (body != null) {
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(body)
                }
            }

        fun payloadOf(result: ResultActionsDsl) =
            mapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8)).path("payload")

        given("이미지 재생성") {
            `when`("READY 음식을 재생성하면") {
                then("PENDING_IMAGE 로 내리고 배치 항목 id 와 함께 응답한다") {
                    val food = saveFood("재생성음식")

                    val payload = payloadOf(regenerate(food.id))
                    payload.path("foodId").asLong() shouldBe food.id
                    payload.path("contentStatus").asText() shouldBe "PENDING_IMAGE"
                    payload.path("batchItemId").asLong() shouldBe itemRepository.findAll().single().id

                    foodRepository.findById(food.id).orElseThrow().contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
                }
            }

            `when`("재생성한 음식에 벡터 삭제가 예약되면") {
                then("아웃박스에 DELETE 가 쌓인다") {
                    val food = saveFood("벡터삭제음식")

                    regenerate(food.id).andExpect { status { isOk() } }

                    vectorOutboxRepository.findAll().count { it.foodId == food.id } shouldBe 1
                }
            }

            `when`("이미 생성이 진행 중인 음식을 다시 재생성하면") {
                then("409 IMAGE-004 로 거절한다") {
                    val food = saveFood("진행중음식")
                    regenerate(food.id).andExpect { status { isOk() } }

                    regenerate(food.id).andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("IMAGE-004") }
                    }
                }
            }

            `when`("발행 시각이 비어 있는 레거시 음식을 재생성하면") {
                then("생성 시각으로 동결된다 — 다시 승인될 때 신규로 뜨지 않게") {
                    val food = saveFood("레거시발행음식")
                    food.publishedAt = null
                    foodRepository.save(food)

                    regenerate(food.id).andExpect { status { isOk() } }

                    val reloaded = foodRepository.findById(food.id).orElseThrow()
                    reloaded.publishedAt shouldBe reloaded.createdAt
                }
            }

            `when`("배치가 실패로 끝나 이미지 대기로 남은 음식을 재생성하면") {
                then("진행 중 항목이 없으므로 다시 제출된다 — 운영자가 복구할 수 있어야 한다") {
                    val food = saveFood("실패잔류음식")
                    regenerate(food.id).andExpect { status { isOk() } }
                    itemRepository.saveAll(
                        itemRepository.findAll().filter { it.foodId == food.id }.onEach { it.fail("배치 실패") },
                    )

                    regenerate(food.id).andExpect { status { isOk() } }
                }
            }

            `when`("첫 이미지를 기다리는 신규 음식을 재생성하면") {
                then("409 FOOD-011 로 거절한다 — 일괄 제출이 맡을 몫이라 유료 호출을 열지 않는다") {
                    val food = foodRepository.save(
                        Food(
                            koreanName = "첫이미지대기음식",
                            description = "설명",
                            contentStatus = FoodContentStatus.PENDING_IMAGE,
                        ),
                    )

                    regenerate(food.id).andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("FOOD-011") }
                    }
                }
            }

            `when`("READY 가 아닌 음식을 재생성하면") {
                then("409 FOOD-011 로 거절한다") {
                    val food = saveFood("검수중음식", FoodContentStatus.PENDING_REVIEW)

                    regenerate(food.id).andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("FOOD-011") }
                    }
                }
            }
        }

        given("이미지 배치 제출") {
            `when`("foodIds 를 지정해 제출하면") {
                then("지정한 음식만 제출되고 기존 필드도 함께 내려간다") {
                    val target = saveFood("지정제출음식", FoodContentStatus.PENDING_IMAGE)
                    saveFood("미지정음식", FoodContentStatus.PENDING_IMAGE)
                    foodRepository.save(foodRepository.findById(target.id).orElseThrow().apply { imageRef = null })

                    val payload = payloadOf(submit(mapOf("foodIds" to listOf(target.id))))
                    payload.path("submittedFoodCount").asInt() shouldBe 1
                    payload.path("submittedCount").asInt() shouldBe 1
                    payload.path("submittedBatchCount").asInt() shouldBe 1
                    payload.path("skippedInProgress").size() shouldBe 0
                }
            }

            `when`("이미지가 이미 있는 READY 음식을 foodIds 로 지정하면") {
                then("후보가 아니라 제출하지 않는다 — 유료 생성 API 를 헛돌리지 않는다") {
                    val ready = saveFood("이미지있는READY음식")

                    val payload = payloadOf(submit(mapOf("foodIds" to listOf(ready.id))))
                    payload.path("submittedFoodCount").asInt() shouldBe 0
                    payload.path("submittedBatchCount").asInt() shouldBe 0
                    payload.path("skippedInProgress").size() shouldBe 0
                }
            }

            `when`("foodIds 를 빈 배열로 보내면") {
                then("전체 일괄로 번지지 않고 아무것도 제출하지 않는다") {
                    val candidate = saveFood("후보음식", FoodContentStatus.PENDING_IMAGE)
                    foodRepository.save(foodRepository.findById(candidate.id).orElseThrow().apply { imageRef = null })

                    val payload = payloadOf(submit(mapOf("foodIds" to emptyList<Long>())))
                    payload.path("submittedFoodCount").asInt() shouldBe 0
                    payload.path("submittedBatchCount").asInt() shouldBe 0
                }
            }

            `when`("바디 없이 제출하면") {
                then("기존처럼 이미지 없는 후보 전체를 일괄 제출한다") {
                    val candidate = saveFood("일괄후보음식", FoodContentStatus.PENDING_IMAGE)
                    foodRepository.save(foodRepository.findById(candidate.id).orElseThrow().apply { imageRef = null })

                    val payload = payloadOf(submit())
                    (payload.path("submittedFoodCount").asInt() >= 1) shouldBe true
                }
            }

            `when`("진행 중인 음식을 foodIds 로 지정하면") {
                then("건너뛰고 skippedInProgress 에 담는다") {
                    val food = saveFood("이미진행음식")
                    regenerate(food.id).andExpect { status { isOk() } }

                    val payload = payloadOf(submit(mapOf("foodIds" to listOf(food.id))))
                    payload.path("submittedFoodCount").asInt() shouldBe 0
                    payload.path("skippedInProgress").size() shouldBe 1
                    payload.path("skippedInProgress")[0].asLong() shouldBe food.id
                }
            }
        }
    }
}
