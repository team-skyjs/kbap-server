package com.kbap.api.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.PATH
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.passedBody
import com.kbap.common.domain.food.FoodContentDraftJpaRepository
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.ImageBatchJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentDraft
import com.kbap.common.domain.food.model.FoodContentDraftStatus
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.food.model.ImageBatch
import com.kbap.common.domain.food.model.ImageBatchItem
import com.kbap.common.domain.food.model.RegenerationIntent
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class AdminFoodContentDraftTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var foodRepository: FoodJpaRepository
    @Autowired private lateinit var outboxRepository: FoodContentOutboxJpaRepository
    @Autowired private lateinit var draftRepository: FoodContentDraftJpaRepository
    @Autowired private lateinit var imageBatchRepository: ImageBatchJpaRepository
    @Autowired private lateinit var imageBatchItemRepository: ImageBatchItemJpaRepository

    private val mapper = jacksonObjectMapper()

    init {
        val admin = 6731L

        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        fun scalar(sql: String): String? = dataSource.connection.use { c ->
            c.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
        }

        fun body(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.getContentAsString(Charsets.UTF_8))

        fun token() = tokenIssuer.issueAccessToken(admin, MemberRole.ADMIN)

        fun publishedFood(status: FoodContentStatus = FoodContentStatus.READY): Food {
            val food = foodRepository.save(
                Food(
                    koreanName = "초안칼국수${System.nanoTime()}",
                    displayName = "초안칼국수",
                    imageRef = "images/webp/food/draft.webp",
                    description = "공개 중인 설명",
                    spiciness = 1,
                    contentStatus = status,
                    ingredients = listOf(FoodIngredient("WHEAT", 90)),
                ),
            )
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute("INSERT INTO food_ingredient (food_id, ingredient_id, inclusion_percent, sort_order) SELECT ${food.id}, id, 90, 10 FROM ingredients WHERE code = 'WHEAT'")
                }
            }
            return food
        }

        fun recollectResult(food: Food, description: String = "재수집이 만든 설명", code: String = "SESAME") {
            val outbox = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
            mockMvc.post(PATH) {
                header("Authorization", "Bearer ${token()}")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(
                    passedBody(food.id, outbox.id, description = description, ingredients = listOf(mapOf("code" to code, "inclusion_percent" to 100))),
                )
            }.andExpect { status { isOk() } }
        }

        fun publicDescription(food: Food): String = body(
            mockMvc.get("/api/foods/${food.id}?lang=en") { header("X-API-Version", "1.0") }.andReturn().response,
        ).path("payload").path("description").asText()

        fun compared(food: Food): JsonNode = body(
            mockMvc.get("/api/admin/foods/${food.id}/content-draft") {
                header("X-API-Version", "1.0")
                header("Authorization", "Bearer ${token()}")
            }.andReturn().response,
        ).path("payload")

        fun review(
            food: Food,
            passed: Boolean,
            reason: String? = null,
            draftId: Long? = draftRepository.findByFoodIdAndReviewStatus(food.id, FoodContentDraftStatus.PENDING)?.id ?: 0L,
            foodVersion: Long? = foodRepository.findById(food.id).orElseThrow().version,
        ) = mockMvc.patch("/api/admin/foods/${food.id}/content-draft") {
            header("X-API-Version", "1.0")
            header("Authorization", "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(mapOf("passed" to passed, "reason" to reason, "draftId" to draftId, "foodVersion" to foodVersion))
        }.andReturn().response

        fun ingredientCodes(food: Food): List<String> = dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT i.code FROM food_ingredient fi JOIN ingredients i ON i.id = fi.ingredient_id WHERE fi.food_id = ${food.id} ORDER BY fi.sort_order")
                    .use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }

        fun upserts(food: Food): Long = scalar("SELECT COUNT(*) FROM food_vector_outbox WHERE food_id = ${food.id} AND operation = 'UPSERT'")!!.toLong()

        given("공개(READY) 음식의 재수집 결과") {
            `when`("결과가 오면") {
                then("공개 응답·재료 표·벡터 큐는 그대로이고 결과는 검수 대기 초안 하나로 남는다") {
                    val food = publishedFood()

                    recollectResult(food)

                    publicDescription(food) shouldBe "공개 중인 설명"
                    foodRepository.findById(food.id).orElseThrow().description shouldBe "공개 중인 설명"
                    ingredientCodes(food) shouldBe listOf("WHEAT")
                    upserts(food) shouldBe 0L
                    val draft = draftRepository.findByFoodIdAndReviewStatus(food.id, FoodContentDraftStatus.PENDING)!!
                    draft.description shouldBe "재수집이 만든 설명"
                    draft.ingredients shouldBe listOf(FoodIngredient("SESAME", 100))
                }
            }

            `when`("검수 대기 초안이 있는데 다시 결과가 오면") {
                then("앞 초안은 대체(SUPERSEDED)되고 새 결과가 검수 대기가 된다 — 음식당 대기 초안은 하나") {
                    val food = publishedFood()
                    recollectResult(food, description = "첫 결과")

                    recollectResult(food, description = "둘째 결과")

                    scalar("SELECT COUNT(*) FROM food_content_draft WHERE food_id = ${food.id} AND review_status = 'PENDING'") shouldBe "1"
                    scalar("SELECT description FROM food_content_draft WHERE food_id = ${food.id} AND review_status = 'SUPERSEDED'") shouldBe "첫 결과"
                    draftRepository.findByFoodIdAndReviewStatus(food.id, FoodContentDraftStatus.PENDING)!!.description shouldBe "둘째 결과"
                }
            }

            `when`("이미지 재생성 중인 READY 음식이면") {
                then("재생성 중이어도 초안으로 간다 — 공개 내용은 그대로") {
                    val food = publishedFood()
                    val batch = imageBatchRepository.save(ImageBatch(promptVersion = "v1", model = "gpt-image-2"))
                    imageBatchItemRepository.save(ImageBatchItem(batchId = batch.id, foodId = food.id, regenerationIntent = RegenerationIntent.WRONG_IMAGE))

                    recollectResult(food)

                    foodRepository.findById(food.id).orElseThrow().description shouldBe "공개 중인 설명"
                    draftRepository.existsByFoodIdAndReviewStatus(food.id, FoodContentDraftStatus.PENDING) shouldBe true
                }
            }

            `when`("결과의 재료가 저장 규칙(중복 코드)을 어기면") {
                then("초안을 만들지 않고 거절하며 요청은 완료로 닫지 않는다 — 콜백이 재시도할 수 있다") {
                    val food = publishedFood()
                    val outbox = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))

                    val response = mockMvc.post(PATH) {
                        header("Authorization", "Bearer ${token()}")
                        contentType = MediaType.APPLICATION_JSON
                        content = mapper.writeValueAsString(
                            passedBody(
                                food.id,
                                outbox.id,
                                ingredients = listOf(mapOf("code" to "SESAME", "inclusion_percent" to 50), mapOf("code" to "SESAME", "inclusion_percent" to 40)),
                            ),
                        )
                    }.andReturn().response

                    response.status shouldBe 400
                    body(response).path("code").asText() shouldBe "FOOD-014"
                    scalar("SELECT COUNT(*) FROM food_content_draft WHERE food_id = ${food.id}") shouldBe "0"
                    scalar("SELECT outbox_status FROM food_content_outbox WHERE id = ${outbox.id}") shouldBe "PENDING"
                }
            }

            `when`("결과에 비율 0% 재료가 섞여 있으면") {
                then("초안에는 승인 때 실제로 반영될 정규화된 재료만 남는다 — 비교 화면과 공개 결과가 같다") {
                    val food = publishedFood()
                    val outbox = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))

                    mockMvc.post(PATH) {
                        header("Authorization", "Bearer ${token()}")
                        contentType = MediaType.APPLICATION_JSON
                        content = mapper.writeValueAsString(
                            passedBody(
                                food.id,
                                outbox.id,
                                ingredients = listOf(mapOf("code" to "SESAME", "inclusion_percent" to 80), mapOf("code" to "WHEAT", "inclusion_percent" to 0)),
                            ),
                        )
                    }.andExpect { status { isOk() } }

                    draftRepository.findByFoodIdAndReviewStatus(food.id, FoodContentDraftStatus.PENDING)!!.ingredients shouldBe listOf(FoodIngredient("SESAME", 80))
                }
            }

            `when`("검수 대기 초안이 있는 음식이 READY 를 벗어난 사이 새 결과가 오면") {
                then("새 결과는 바로 반영되고 옛 초안은 SUPERSEDED — 다시 READY 가 돼도 옛 결과가 새 결과를 덮을 수 없다") {
                    val food = publishedFood()
                    recollectResult(food, description = "옛 결과")
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE food SET content_status = 'PENDING_IMAGE' WHERE id = ${food.id}") } }

                    recollectResult(food, description = "새 결과")

                    foodRepository.findById(food.id).orElseThrow().description shouldBe "새 결과"
                    scalar("SELECT review_status FROM food_content_draft WHERE food_id = ${food.id} AND description = '옛 결과'") shouldBe "SUPERSEDED"
                    draftRepository.existsByFoodIdAndReviewStatus(food.id, FoodContentDraftStatus.PENDING) shouldBe false
                }
            }

            `when`("READY 가 아닌 음식에 결과가 오면") {
                then("종전대로 바로 반영되고 초안은 없다") {
                    val food = publishedFood(FoodContentStatus.FAILED)

                    recollectResult(food)

                    foodRepository.findById(food.id).orElseThrow().description shouldBe "재수집이 만든 설명"
                    scalar("SELECT COUNT(*) FROM food_content_draft WHERE food_id = ${food.id}") shouldBe "0"
                }
            }
        }

        given("초안 비교 — GET /api/admin/foods/{foodId}/content-draft") {
            `when`("검수 대기 초안이 있으면") {
                then("지금 공개 값과 초안 값을 나란히 준다") {
                    val food = publishedFood()
                    recollectResult(food)

                    val payload = body(
                        mockMvc.get("/api/admin/foods/${food.id}/content-draft") {
                            header("X-API-Version", "1.0")
                            header("Authorization", "Bearer ${token()}")
                        }.andReturn().response,
                    ).path("payload")

                    payload.path("current").path("description").asText() shouldBe "공개 중인 설명"
                    payload.path("current").path("ingredients")[0].path("code").asText() shouldBe "WHEAT"
                    payload.path("draft").path("description").asText() shouldBe "재수집이 만든 설명"
                    payload.path("draft").path("ingredients")[0].path("code").asText() shouldBe "SESAME"
                }
            }

            `when`("초안이 있는 음식이 삭제됐으면") {
                then("목록과 전체 수에서 함께 빠진다 — 페이지가 비었는데 수가 남지 않는다") {
                    val food = publishedFood()
                    recollectResult(food)
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE food SET status = 'DELETED' WHERE id = ${food.id}") } }

                    val payload = body(
                        mockMvc.get("/api/admin/foods/content-drafts") {
                            header("X-API-Version", "1.0")
                            header("Authorization", "Bearer ${token()}")
                        }.andReturn().response,
                    ).path("payload")

                    payload.path("items").size() shouldBe 0
                    payload.path("totalCount").asLong() shouldBe 0L
                }
            }

            `when`("삭제된 음식의 초안과 활성 초안이 섞여 있고 페이지가 꽉 차면(전체 수 질의가 실제로 돌면)") {
                then("전체 수도 활성 음식 초안만 센다") {
                    val deleted = publishedFood()
                    recollectResult(deleted)
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE food SET status = 'DELETED' WHERE id = ${deleted.id}") } }
                    val active = publishedFood()
                    recollectResult(active)

                    val payload = body(
                        mockMvc.get("/api/admin/foods/content-drafts?size=1") {
                            header("X-API-Version", "1.0")
                            header("Authorization", "Bearer ${token()}")
                        }.andReturn().response,
                    ).path("payload")

                    payload.path("items").map { it.path("foodId").asLong() } shouldBe listOf(active.id)
                    payload.path("totalCount").asLong() shouldBe 1L
                }
            }

            `when`("목록을 보면") {
                then("검수 대기 초안만 나온다") {
                    val food = publishedFood()
                    recollectResult(food)
                    val other = publishedFood()
                    recollectResult(other)
                    review(other, passed = false).status shouldBe 200

                    val items = body(
                        mockMvc.get("/api/admin/foods/content-drafts") {
                            header("X-API-Version", "1.0")
                            header("Authorization", "Bearer ${token()}")
                        }.andReturn().response,
                    ).path("payload").path("items")

                    items.map { it.path("foodId").asLong() } shouldBe listOf(food.id)
                }
            }
        }

        given("초안 검수 — PATCH /api/admin/foods/{foodId}/content-draft") {
            `when`("승인하면") {
                then("공개 내용·재료 표가 초안으로 바뀌고 벡터 UPSERT 가 예약되며 초안은 APPROVED(처리자·시각) — 음식은 READY 그대로") {
                    val food = publishedFood()
                    recollectResult(food)

                    val response = review(food, passed = true)

                    response.status shouldBe 200
                    body(response).path("payload").path("reviewStatus").asText() shouldBe "APPROVED"
                    publicDescription(food) shouldBe "noodle-en"
                    foodRepository.findById(food.id).orElseThrow().description shouldBe "재수집이 만든 설명"
                    foodRepository.findById(food.id).orElseThrow().contentStatus shouldBe FoodContentStatus.READY
                    ingredientCodes(food) shouldBe listOf("SESAME")
                    upserts(food) shouldBe 1L
                    scalar("SELECT resolved_by FROM food_content_draft WHERE food_id = ${food.id} AND review_status = 'APPROVED'") shouldBe "$admin"
                }
            }

            `when`("반려하면") {
                then("공개 내용은 그대로이고 초안은 사유와 함께 REJECTED") {
                    val food = publishedFood()
                    recollectResult(food)

                    review(food, passed = false, reason = "재료 오추출").status shouldBe 200

                    publicDescription(food) shouldBe "공개 중인 설명"
                    ingredientCodes(food) shouldBe listOf("WHEAT")
                    upserts(food) shouldBe 0L
                    scalar("SELECT reject_reason FROM food_content_draft WHERE food_id = ${food.id} AND review_status = 'REJECTED'") shouldBe "재료 오추출"
                }
            }

            `when`("초안이 쌓인 뒤 음식이 이미지 재생성에 들어가 READY 를 벗어났으면") {
                then("승인하지 않고 409 FOOD-020 — 음식 상태·내용은 그대로, 초안은 대기로 남아 재생성 뒤 승인할 수 있다") {
                    val food = publishedFood()
                    recollectResult(food)
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE food SET content_status = 'PENDING_IMAGE' WHERE id = ${food.id}") } }

                    val response = review(food, passed = true)

                    response.status shouldBe 409
                    body(response).path("code").asText() shouldBe "FOOD-020"
                    scalar("SELECT content_status FROM food WHERE id = ${food.id}") shouldBe "PENDING_IMAGE"
                    scalar("SELECT description FROM food WHERE id = ${food.id}") shouldBe "공개 중인 설명"
                    draftRepository.existsByFoodIdAndReviewStatus(food.id, FoodContentDraftStatus.PENDING) shouldBe true
                }
            }

            `when`("비교 화면을 본 뒤 새 결과가 와서 초안이 대체됐으면") {
                then("본 초안 id 로 승인하면 FOOD-021 — 보지 않은 새 초안을 처리하지 않는다") {
                    val food = publishedFood()
                    recollectResult(food, description = "본 초안")
                    val seen = compared(food)
                    recollectResult(food, description = "보지 않은 새 초안")

                    val response = review(food, passed = true, draftId = seen.path("draftId").asLong(), foodVersion = seen.path("foodVersion").asLong())

                    response.status shouldBe 404
                    body(response).path("code").asText() shouldBe "FOOD-021"
                    foodRepository.findById(food.id).orElseThrow().description shouldBe "공개 중인 설명"
                    draftRepository.findByFoodIdAndReviewStatus(food.id, FoodContentDraftStatus.PENDING)!!.description shouldBe "보지 않은 새 초안"
                }
            }

            `when`("비교 화면을 본 뒤 다른 관리자가 공개 내용을 고쳤으면") {
                then("본 버전으로 승인하면 409 FOOD-006 — 더 새 공개 내용을 초안으로 덮지 않는다") {
                    val food = publishedFood()
                    recollectResult(food)
                    val seen = compared(food)
                    dataSource.connection.use { c ->
                        c.createStatement().use { it.execute("UPDATE food SET description = '다른 관리자의 수정', version = version + 1 WHERE id = ${food.id}") }
                    }

                    val response = review(food, passed = true, draftId = seen.path("draftId").asLong(), foodVersion = seen.path("foodVersion").asLong())

                    response.status shouldBe 409
                    body(response).path("code").asText() shouldBe "FOOD-006"
                    foodRepository.findById(food.id).orElseThrow().description shouldBe "다른 관리자의 수정"
                    draftRepository.existsByFoodIdAndReviewStatus(food.id, FoodContentDraftStatus.PENDING) shouldBe true
                }
            }

            `when`("검수 대기 초안이 없으면") {
                then("404 FOOD-021") {
                    val food = publishedFood()

                    val response = review(food, passed = true)

                    response.status shouldBe 404
                    body(response).path("code").asText() shouldBe "FOOD-021"
                }
            }

            `when`("초안 재료 코드가 지금 카탈로그에 없으면") {
                then("승인하지 않고 400 FOOD-016 — 공개 내용은 그대로, 초안은 검수 대기로 남아 반려로 정리할 수 있다") {
                    val food = publishedFood()
                    val outbox = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
                    draftRepository.save(
                        FoodContentDraft(
                            foodId = food.id,
                            outboxId = outbox.id,
                            description = "카탈로그에서 빠진 재료의 초안",
                            spiciness = 2,
                            ingredients = listOf(FoodIngredient("REMOVED_FROM_CATALOG", 100)),
                        ),
                    )

                    val response = review(food, passed = true)

                    response.status shouldBe 400
                    body(response).path("code").asText() shouldBe "FOOD-016"
                    publicDescription(food) shouldBe "공개 중인 설명"
                    draftRepository.existsByFoodIdAndReviewStatus(food.id, FoodContentDraftStatus.PENDING) shouldBe true
                    review(food, passed = false).status shouldBe 200
                }
            }
        }

        given("단건 재수집 응답") {
            `when`("검수 대기 초안이 있는 음식을 다시 재수집하면") {
                then("접수되고 pendingDraft = true 로 알린다") {
                    val food = publishedFood()
                    recollectResult(food)

                    val payload = body(
                        mockMvc.post("/api/admin/foods/${food.id}/recollect") {
                            header("X-API-Version", "1.0")
                            header("Authorization", "Bearer ${token()}")
                        }.andReturn().response,
                    ).path("payload")

                    payload.path("created").asLong() shouldBe 1L
                    payload.path("pendingDraft").asBoolean() shouldBe true
                }
            }
        }

        given("api-docs") {
            `when`("문서를 보면") {
                then("초안 목록·비교·검수 경로가 실리고, 검수 경로 설명에 사람 검수 전용이라고 적혀 있다") {
                    val paths = mapper.readTree(mockMvc.get("/v3/api-docs").andReturn().response.getContentAsString(Charsets.UTF_8)).path("paths")

                    paths.has("/api/admin/foods/content-drafts") shouldBe true
                    paths.path("/api/admin/foods/{foodId}/content-draft").has("get") shouldBe true
                    paths.path("/api/admin/foods/{foodId}/content-draft").path("patch").path("summary").asText().contains("사람 검수 전용") shouldBe true
                    paths.path("/api/admin/foods/contents").path("post").path("description").asText().contains("검수 초안") shouldBe true
                }
            }
        }

        given("재수집 API 문서") {
            `when`("단건·일괄 재수집 오퍼레이션 설명을 보면") {
                then("READY 음식은 결과가 검수 초안이 되고 승인 전 공개 무변이라고 적혀 있고, 검수 없는 덮어쓰기 문구는 없다") {
                    val paths = mapper.readTree(mockMvc.get("/v3/api-docs").andReturn().response.getContentAsString(Charsets.UTF_8)).path("paths")
                    val single = paths.path("/api/admin/foods/{id}/recollect").path("post").path("description").asText()
                    val bulk = paths.path("/api/admin/foods/recollect").path("post").path("description").asText()

                    listOf(single, bulk).forEach { description ->
                        description.contains("검수 대기 초안") shouldBe true
                        description.contains("승인 전") shouldBe true
                        description.contains("검수 없는 덮어쓰기") shouldBe false
                    }
                    single.contains("pendingDraft") shouldBe true
                }
            }
        }

        given("초안 엔티티") {
            `when`("맵기가 범위(-1~10) 밖이면") {
                then("만들 때 거절한다 — food 의 CHECK 제약에서 승인 시점에 터지지 않게") {
                    shouldThrow<IllegalArgumentException> { FoodContentDraft(foodId = 1, outboxId = 1, spiciness = 11) }
                }
            }
        }
    }
}
