package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.admin.AdminTestTokens.adminHeaders
import com.kbap.api.food.FakeFoodImageBatchClient
import com.kbap.api.image.FakeStorageObjectStore
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.ImageBatchJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminFoodImageReplaceTest : BehaviorSpec() {
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
    private lateinit var vectorOutboxRepository: FoodVectorOutboxJpaRepository

    @Autowired
    private lateinit var fakeClient: FakeFoodImageBatchClient

    @Autowired
    private lateinit var fakeStorage: FakeStorageObjectStore

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val objectMapper = jacksonObjectMapper()

    init {
        fun seed(name: String, status: FoodContentStatus, imageRef: String? = "images/food/$name.webp"): Food =
            foodRepository.save(Food(koreanName = name, displayName = name, imageRef = imageRef, description = "설명", contentStatus = status))

        fun post(path: String, body: String? = null): MvcResult =
            mockMvc.post("/api/admin/foods$path") {
                adminHeaders(AdminTestTokens.adminAccessToken(tokenIssuer))
                contentType = MediaType.APPLICATION_JSON
                body?.let { content = it }
            }.andReturn()

        fun put(path: String, body: String): MvcResult =
            mockMvc.put("/api/admin/foods$path") {
                adminHeaders(AdminTestTokens.adminAccessToken(tokenIssuer))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()

        fun json(r: MvcResult): Map<String, Any?> = objectMapper.readValue(r.response.contentAsString)

        @Suppress("UNCHECKED_CAST")
        fun payload(r: MvcResult) = json(r)["payload"] as Map<String, Any?>

        beforeContainer {
            itemRepository.deleteAll()
            batchRepository.deleteAll()
            vectorOutboxRepository.deleteAll()
            foodRepository.deleteAll()
            fakeClient.reset()
            fakeStorage.heads.clear()
        }

        given("이미지 재생성") {
            `when`("READY 음식에 요청하면") {
                then("상태를 유지한 채 단건 배치·아이템이 생기고, 진행 중 재요청은 409") {
                    val food = seed("재생성음식", FoodContentStatus.READY)

                    val result = post("/${food.id}/image/regenerate")

                    result.response.status shouldBe 200
                    val p = payload(result)
                    batchRepository.count() shouldBe 1
                    itemRepository.findTop10ByFoodIdOrderByIdDesc(food.id).single().id shouldBe (p["itemId"] as Int).toLong()
                    foodRepository.findById(food.id).get().contentStatus shouldBe FoodContentStatus.READY
                    fakeClient.submitted.single().single().customId shouldBe food.id.toString()

                    val again = post("/${food.id}/image/regenerate")
                    again.response.status shouldBe 409
                    json(again)["code"] shouldBe "FOOD-009"
                }
            }
        }

        given("이미지 업로드 교체") {
            `when`("업로드 URL 을 발급받으면") {
                then("음식 전용 키와 사전 서명 URL 이 오고, 형식·크기 위반은 400") {
                    val food = seed("업로드음식", FoodContentStatus.READY)

                    val ok = post("/${food.id}/image/upload-url", """{"contentType":"image/png","contentLength":1024}""")
                    ok.response.status shouldBe 200
                    (payload(ok)["objectKey"] as String) shouldContain "images/food/"

                    val badType = post("/${food.id}/image/upload-url", """{"contentType":"text/plain","contentLength":1024}""")
                    json(badType)["code"] shouldBe "UPLOAD-001"

                    val tooBig = post("/${food.id}/image/upload-url", """{"contentType":"image/png","contentLength":99999999}""")
                    json(tooBig)["code"] shouldBe "UPLOAD-003"
                }
            }

            `when`("업로드된 키로 교체하면") {
                then("READY 는 유지되고 imageRef 가 바뀌며 벡터 UPSERT 가 예약된다") {
                    val food = seed("교체음식", FoodContentStatus.READY)
                    fakeStorage.stub("local/images/food/2026/08/1_abc.png", "image/png", 1024)

                    val result = put("/${food.id}/image", """{"objectKey":"local/images/food/2026/08/1_abc.png"}""")

                    result.response.status shouldBe 200
                    foodRepository.findById(food.id).get().let {
                        it.imageRef shouldBe "local/images/food/2026/08/1_abc.png"
                        it.contentStatus shouldBe FoodContentStatus.READY
                    }
                    vectorOutboxRepository.existsByFoodIdAndOperationAndOutboxStatus(food.id, FoodVectorOutboxOperation.UPSERT, FoodVectorOutboxStatus.PENDING) shouldBe true
                }
            }

            `when`("저장소에 없는 키로 교체하면") {
                then("400 IMAGE-003 이고 기존 이미지가 유지된다") {
                    val food = seed("없는키음식", FoodContentStatus.READY)

                    val result = put("/${food.id}/image", """{"objectKey":"local/images/food/missing.png"}""")

                    result.response.status shouldBe 400
                    json(result)["code"] shouldBe "IMAGE-003"
                    foodRepository.findById(food.id).get().imageRef shouldBe "images/food/없는키음식.webp"
                }
            }

            `when`("이미지 대기 음식을 교체하면") {
                then("승인 대기로 넘어간다") {
                    val food = seed("대기교체음식", FoodContentStatus.PENDING_IMAGE, imageRef = null)
                    fakeStorage.stub("local/images/food/2026/08/1_new.webp", "image/webp", 10)

                    put("/${food.id}/image", """{"objectKey":"local/images/food/2026/08/1_new.webp"}""").response.status shouldBe 200

                    foodRepository.findById(food.id).get().contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                }
            }
        }
    }
}
