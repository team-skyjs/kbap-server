package com.kbap.api.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.food.FakeFoodImageBatchClient
import com.kbap.api.food.FoodImageBatchSubmitService
import com.kbap.api.food.FoodImageProperties
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.ImageBatchJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.RegenerationIntent
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

@IntegrationTest
class AdminFoodImageFeedbackTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var foodRepository: FoodJpaRepository
    @Autowired private lateinit var itemRepository: ImageBatchItemJpaRepository
    @Autowired private lateinit var batchRepository: ImageBatchJpaRepository
    @Autowired private lateinit var submitService: FoodImageBatchSubmitService
    @Autowired private lateinit var properties: FoodImageProperties
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var fakeClient: FakeFoodImageBatchClient
    @Autowired private lateinit var dataSource: DataSource

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun clear() {
            listOf("food_vector_outbox", "food_content_outbox", "image_batch_item", "image_batch", "food_image", "food").forEach { execute("DELETE FROM $it") }
        }

        beforeEach {
            clear()
            fakeClient.reset()
        }
        afterSpec { clear() }

        fun token(): String = tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)

        fun saveFood(
            name: String,
            status: FoodContentStatus = FoodContentStatus.READY,
            imageRef: String? = "images/webp/$name.webp",
            failureKind: FoodContentFailureKind? = null,
        ): Food = foodRepository.save(
            Food(koreanName = name, description = "설명", imageRef = imageRef, contentStatus = status).apply { contentFailureKind = failureKind },
        )

        fun regenerate(foodId: Long, intent: String?, reason: String? = null): ResultActionsDsl =
            mockMvc.post("/api/admin/foods/$foodId/regenerate-image") {
                header("Authorization", "Bearer ${token()}")
                header("X-API-Version", "1.0")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("intent" to intent, "reason" to reason).filterValues { it != null })
            }

        fun read(result: ResultActionsDsl, expected: Int = 200): JsonNode =
            mapper.readTree(result.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8))

        fun lastPrompt(): String = fakeClient.submitted.last().single().prompt

        fun correctionBlock(reason: String) =
            "이번 이미지의 관리자 수정 지시: $reason\n위 지시를 이번 이미지의 음식 표현에 반영한다. 음식의 정체성과 기존 촬영·출력 형식 규칙을 유지한다."

        given("이미지 재생성 수정 지시 → 생성 프롬프트(KB-928)") {
            `when`("WRONG_IMAGE 사유를 남겨 재생성하면") {
                then("배치 클라이언트에 넘기는 entry 의 prompt 가 기존 템플릿 + 수정 지시 블록이고 배치는 v2 로 기록된다") {
                    val food = saveFood("막국수")

                    read(regenerate(food.id, "WRONG_IMAGE", "막국수는 보통 빨간 비빔 양념으로 되어있음."))

                    val entry = fakeClient.submitted.single().single()
                    entry.customId shouldBe food.id.toString()
                    entry.prompt shouldBe properties.promptFor("막국수") + "\n\n" + correctionBlock("막국수는 보통 빨간 비빔 양념으로 되어있음.")
                    batchRepository.findAll().single().promptVersion shouldBe "v2"
                }
            }

            `when`("사유 없이 재생성하거나 추가 생성하거나 일괄 생성하면") {
                then("prompt 는 기존 템플릿 그대로이고 배치는 v2 다") {
                    val plain = saveFood("물냉면")
                    read(regenerate(plain.id, "WRONG_IMAGE"))
                    lastPrompt() shouldBe properties.promptFor("물냉면")
                    lastPrompt() shouldNotContain "관리자 수정 지시"

                    val ready = saveFood("비빔냉면")
                    read(mockMvc.post("/api/admin/foods/${ready.id}/images/generate") { header("Authorization", "Bearer ${token()}"); header("X-API-Version", "1.0") })
                    lastPrompt() shouldBe properties.promptFor("비빔냉면")

                    val fresh = foodRepository.save(Food(koreanName = "회냉면", description = "설명", imageRef = null, contentStatus = FoodContentStatus.PENDING_IMAGE))
                    submitService.submitForFoods(listOf(fresh.id))
                    lastPrompt() shouldBe properties.promptFor("회냉면")

                    batchRepository.findAll().map { it.promptVersion }.toSet() shouldBe setOf("v2")
                }
            }

            `when`("공백뿐인 사유면") {
                then("수정 지시 블록을 붙이지 않는다") {
                    val food = saveFood("공백사유음식")
                    read(regenerate(food.id, "WRONG_IMAGE", "   "))
                    lastPrompt() shouldBe properties.promptFor("공백사유음식")
                }
            }

            `when`("두 음식을 서로 다른 사유로 재생성하면") {
                then("각 제출 entry 에는 자기 사유만 들어간다") {
                    val a = saveFood("가음식")
                    val b = saveFood("나음식")

                    read(regenerate(a.id, "WRONG_IMAGE", "가 사유"))
                    read(regenerate(b.id, "REPLACE_BETTER", "나 사유"))

                    val (first, second) = fakeClient.submitted.map { it.single() }
                    first.prompt shouldContain "관리자 수정 지시: 가 사유"
                    first.prompt shouldNotContain "나 사유"
                    second.prompt shouldContain "관리자 수정 지시: 나 사유"
                    second.prompt shouldNotContain "가 사유"
                }
            }

            `when`("선점한 뒤 제출 전에 그 항목의 사유가 DB 에서 바뀌어도") {
                then("제출은 선점 때 고정한 입력을 쓴다") {
                    val food = saveFood("고정입력음식")
                    val claim = TransactionTemplate(transactionManager).execute {
                        submitService.claimOne(foodRepository.findById(food.id).orElseThrow(), RegenerationIntent.WRONG_IMAGE, "선점 때 사유")
                    }!!
                    execute("UPDATE image_batch_item SET regeneration_reason = '나중 사유' WHERE id = ${claim.itemId}")

                    submitService.submitClaimed(claim)

                    lastPrompt() shouldContain "관리자 수정 지시: 선점 때 사유"
                    lastPrompt() shouldNotContain "나중 사유"
                }
            }
        }

        given("WRONG_IMAGE 재생성 허용 상태(KB-928)") {
            `when`("검수 대기(PENDING_REVIEW)·관리자 반려(FAILED+ADMIN_REJECTED) 음식에 이미지가 있으면") {
                then("WRONG_IMAGE 재생성을 받아 PENDING_IMAGE 로 내린다") {
                    val reviewing = saveFood("검수대기음식", FoodContentStatus.PENDING_REVIEW)
                    val rejected = saveFood("반려음식", FoodContentStatus.FAILED, failureKind = FoodContentFailureKind.ADMIN_REJECTED)

                    read(regenerate(reviewing.id, "WRONG_IMAGE", "빨간 양념")).path("payload").path("contentStatus").asText() shouldBe "PENDING_IMAGE"
                    read(regenerate(rejected.id, "WRONG_IMAGE", "빨간 양념")).path("payload").path("contentStatus").asText() shouldBe "PENDING_IMAGE"

                    foodRepository.findById(reviewing.id).orElseThrow().contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
                    foodRepository.findById(rejected.id).orElseThrow().contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
                }
            }

            `when`("그 밖의 의도·상태면") {
                then("409 FOOD-011 이고 상태를 바꾸지 않는다") {
                    val reviewing = saveFood("검수대기2", FoodContentStatus.PENDING_REVIEW)
                    val judgeRejected = saveFood("모델반려음식", FoodContentStatus.FAILED, failureKind = FoodContentFailureKind.JUDGE_REJECTED)
                    val noImage = saveFood("이미지없는반려", FoodContentStatus.FAILED, imageRef = null, failureKind = FoodContentFailureKind.ADMIN_REJECTED)

                    read(regenerate(reviewing.id, "REPLACE_BETTER"), 409).path("code").asText() shouldBe "FOOD-011"
                    read(regenerate(reviewing.id, null), 409).path("code").asText() shouldBe "FOOD-011"
                    read(regenerate(judgeRejected.id, "WRONG_IMAGE", "사유"), 409).path("code").asText() shouldBe "FOOD-011"
                    read(regenerate(noImage.id, "WRONG_IMAGE", "사유"), 409).path("code").asText() shouldBe "FOOD-011"

                    foodRepository.findById(reviewing.id).orElseThrow().contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                    foodRepository.findById(judgeRejected.id).orElseThrow().contentStatus shouldBe FoodContentStatus.FAILED
                    fakeClient.submitted.size shouldBe 0
                }
            }

            `when`("검수 대기 음식에 이미지 생성이 이미 진행 중이면") {
                then("409 IMAGE-004 다") {
                    val food = saveFood("진행중음식", FoodContentStatus.PENDING_REVIEW)
                    read(regenerate(food.id, "WRONG_IMAGE", "첫 요청"))
                    execute("UPDATE food SET content_status = 'PENDING_REVIEW' WHERE id = ${food.id}")

                    read(regenerate(food.id, "WRONG_IMAGE", "두 번째"), 409).path("code").asText() shouldBe "IMAGE-004"
                }
            }
        }

        given("결과 이미지의 생성 지시 조회 — generation(KB-928)") {
            `when`("갤러리·상세를 보면") {
                then("각 이미지는 그 이미지를 만든 배치 항목의 의도·사유·프롬프트 버전·모델·제출 시각을, 이력이 없으면 null 을 받는다") {
                    val food = saveFood("이력음식")
                    val generated = "images/webp/food/gen_v2.webp"
                    val legacy = "images/webp/food/gen_v1.webp"
                    execute("UPDATE food SET image_ref = '$generated' WHERE id = ${food.id}")
                    execute(
                        "INSERT INTO food_image (food_id, image_key, is_primary, sort_order, source, status, created_at, updated_at) VALUES " +
                            "(${food.id}, '$generated', 1, 0, 'GENERATED', 'ACTIVE', NOW(6), NOW(6))," +
                            "(${food.id}, '$legacy', 0, 1, 'GENERATED', 'ACTIVE', NOW(6), NOW(6))," +
                            "(${food.id}, 'images/webp/food/uploaded.webp', 0, 2, 'GENERATED', 'ACTIVE', NOW(6), NOW(6))",
                    )
                    execute(
                        "INSERT INTO image_batch (openai_batch_id, batch_status, prompt_version, model, submitted_at, status, created_at, updated_at) VALUES " +
                            "('b_v2', 'COLLECTED', 'v2', 'gpt-image-2', '2026-10-30 09:00:00', 'ACTIVE', NOW(6), NOW(6))," +
                            "('b_v1', 'COLLECTED', 'v1', 'gpt-image-2', '2026-10-29 04:24:43', 'ACTIVE', NOW(6), NOW(6))",
                    )
                    val v2Batch = dataSource.connection.use { c -> c.createStatement().use { st -> st.executeQuery("SELECT id FROM image_batch WHERE openai_batch_id = 'b_v2'").use { rs -> rs.next(); rs.getLong(1) } } }
                    val v1Batch = dataSource.connection.use { c -> c.createStatement().use { st -> st.executeQuery("SELECT id FROM image_batch WHERE openai_batch_id = 'b_v1'").use { rs -> rs.next(); rs.getLong(1) } } }
                    execute(
                        "INSERT INTO image_batch_item (batch_id, food_id, item_status, file_name, regeneration_intent, regeneration_reason, status, created_at, updated_at) VALUES " +
                            "($v2Batch, ${food.id}, 'DONE', '$generated', 'WRONG_IMAGE', '빨간 비빔 양념', 'ACTIVE', NOW(6), NOW(6))," +
                            "($v1Batch, ${food.id}, 'DONE', '$legacy', 'WRONG_IMAGE', '옛 사유', 'ACTIVE', NOW(6), NOW(6))",
                    )

                    val gallery = read(mockMvc.get("/api/admin/foods/${food.id}/images") { header("Authorization", "Bearer ${token()}"); header("X-API-Version", "1.0") }).path("payload")
                    val byKey = gallery.path("items").associateBy { it.path("imageKey").asText() }
                    byKey.getValue(generated).path("generation").let {
                        it.path("intent").asText() shouldBe "WRONG_IMAGE"
                        it.path("reason").asText() shouldBe "빨간 비빔 양념"
                        it.path("promptVersion").asText() shouldBe "v2"
                        it.path("model").asText() shouldBe "gpt-image-2"
                        it.path("submittedAt").asText() shouldBe "2026-10-30T09:00:00"
                        it.path("reasonInPrompt").asBoolean() shouldBe true
                    }
                    byKey.getValue(legacy).path("generation").let {
                        it.path("reason").asText() shouldBe "옛 사유"
                        it.path("promptVersion").asText() shouldBe "v1"
                        it.path("reasonInPrompt").asBoolean() shouldBe false
                    }
                    byKey.getValue("images/webp/food/uploaded.webp").path("generation").isNull shouldBe true
                    gallery.path("regeneration").isNull shouldBe true

                    val detail = read(mockMvc.get("/api/admin/foods/${food.id}") { header("Authorization", "Bearer ${token()}"); header("X-API-Version", "1.0") }).path("payload")
                    detail.path("generation").path("reason").asText() shouldBe "빨간 비빔 양념"
                    detail.path("generation").path("promptVersion").asText() shouldBe "v2"
                }
            }
        }
    }
}
