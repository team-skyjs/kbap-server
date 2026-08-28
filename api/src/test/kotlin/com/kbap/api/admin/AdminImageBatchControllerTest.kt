package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.admin.AdminTestTokens.adminHeaders
import com.kbap.api.food.FakeFoodImageBatchClient
import com.kbap.api.food.FoodImageBatchCollectService
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.ImageBatchJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.ImageBatch
import com.kbap.common.domain.food.model.ImageBatchItem
import com.kbap.common.domain.food.model.ImageBatchItemStatus
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.llm.FoodImageBatchClient
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminImageBatchControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var batchRepository: ImageBatchJpaRepository

    @Autowired
    private lateinit var itemRepository: ImageBatchItemJpaRepository

    @Autowired
    private lateinit var fakeClient: FakeFoodImageBatchClient

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val objectMapper = jacksonObjectMapper()

    init {
        fun seed(name: String, status: FoodContentStatus): Food =
            foodRepository.save(Food(koreanName = name, displayName = name, description = "설명", contentStatus = status))

        fun submittedBatch(vararg foodIds: Long): ImageBatch {
            val batch = batchRepository.save(ImageBatch(promptVersion = "v1", model = "gpt-image-2").apply { markSubmitted("batch_${System.nanoTime()}") })
            foodIds.forEach { itemRepository.save(ImageBatchItem(batchId = batch.id, foodId = it)) }
            return batch
        }

        fun get(path: String): MvcResult = mockMvc.get("/api/admin/foods/images$path") { adminHeaders(AdminTestTokens.adminAccessToken(tokenIssuer)) }.andReturn()

        fun post(path: String, body: String? = null): MvcResult =
            mockMvc.post("/api/admin/foods/images$path") {
                adminHeaders(AdminTestTokens.adminAccessToken(tokenIssuer))
                contentType = MediaType.APPLICATION_JSON
                body?.let { content = it }
            }.andReturn()

        fun json(r: MvcResult): Map<String, Any?> = objectMapper.readValue(r.response.contentAsString)

        @Suppress("UNCHECKED_CAST")
        fun payload(r: MvcResult) = json(r)["payload"] as Map<String, Any?>

        beforeContainer {
            itemRepository.deleteAll()
            batchRepository.deleteAll()
            foodRepository.deleteAll()
            fakeClient.reset()
        }

        given("이미지 배치 조회") {
            `when`("목록·후보 수·상세를 조회하면") {
                then("외부 식별자·프롬프트 버전·아이템 사유가 온다") {
                    val pendingImage = seed("후보음식", FoodContentStatus.PENDING_IMAGE)
                    val other = seed("배치음식", FoodContentStatus.READY)
                    val batch = submittedBatch(other.id)
                    itemRepository.findByBatchIdOrderByIdAsc(batch.id).single().let { itemRepository.save(it.apply { fail("정책 거절") }) }

                    @Suppress("UNCHECKED_CAST")
                    val list = payload(get("?page=1&size=20"))["items"] as List<Map<String, Any?>>
                    list.single()["openaiBatchId"] shouldBe batch.openaiBatchId
                    list.single()["promptVersion"] shouldBe "v1"
                    list.single()["failedCount"] shouldBe 1

                    payload(get("/candidates/count"))["count"] shouldBe 1

                    @Suppress("UNCHECKED_CAST")
                    val detailItems = payload(get("/${batch.id}"))["items"] as List<Map<String, Any?>>
                    detailItems.single()["errorMsg"] shouldBe "정책 거절"
                    detailItems.single()["displayName"] shouldBe "배치음식"
                    pendingImage.id shouldBe pendingImage.id
                }
            }
        }

        given("즉시 회수") {
            `when`("완료된 배치가 있을 때 회수하면") {
                then("결과가 반영되고 카운트를 돌려준다") {
                    val food = seed("회수음식", FoodContentStatus.PENDING_IMAGE)
                    val batch = submittedBatch(food.id)
                    fakeClient.polls[batch.openaiBatchId!!] = FoodImageBatchClient.BatchPoll(FoodImageBatchClient.State.COMPLETED, "out-1", null)
                    fakeClient.results["out-1"] = listOf(FoodImageBatchClient.Result(customId = food.id.toString(), bytes = byteArrayOf(1, 2), errorMessage = null, usage = null))

                    val result = payload(post("/collect"))

                    result["collectedBatches"] shouldBe 1
                    result["doneItems"] shouldBe 1
                    foodRepository.findById(food.id).get().contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                }
            }

        }

        given("실패 아이템 재제출") {
            `when`("실패 아이템 id 로 재제출하면") {
                then("그 음식들로 새 배치가 생긴다") {
                    val food = seed("재제출음식", FoodContentStatus.PENDING_IMAGE)
                    val batch = submittedBatch(food.id)
                    val failed = itemRepository.findByBatchIdOrderByIdAsc(batch.id).single().apply { fail("boom") }.let { itemRepository.save(it) }

                    val result = payload(post("/items/resubmit", """{"itemIds":[${failed.id}]}"""))

                    result["itemCount"] shouldBe 1
                    batchRepository.count() shouldBe 2
                    itemRepository.findTop10ByFoodIdOrderByIdDesc(food.id).first().itemStatus shouldBe ImageBatchItemStatus.PENDING
                }
            }
        }
    }
}
