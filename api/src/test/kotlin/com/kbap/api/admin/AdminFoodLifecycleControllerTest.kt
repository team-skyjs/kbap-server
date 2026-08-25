package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.admin.AdminTestTokens.adminHeaders
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminFoodLifecycleControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var contentOutboxRepository: FoodContentOutboxJpaRepository

    @Autowired
    private lateinit var vectorOutboxRepository: FoodVectorOutboxJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val objectMapper = jacksonObjectMapper()

    init {
        val targets = LanguageCode.entries.filter { it != LanguageCode.KO }.map { it.code }
        fun allTargets(v: String) = targets.associateWith { "$v-$it" }

        fun seed(name: String, status: FoodContentStatus = FoodContentStatus.READY, imageRef: String? = "images/food/$name.webp"): Food =
            foodRepository.save(
                Food(
                    koreanName = name, displayName = name, imageRef = imageRef, description = "설명", spiciness = 1,
                    nameTranslations = allTargets(name), descriptionTranslations = allTargets("d"),
                    ingredients = listOf(FoodIngredient("SOY", 100)), contentStatus = status,
                ),
            )

        fun token() = AdminTestTokens.adminAccessToken(tokenIssuer)

        fun post(path: String, body: String? = null): MvcResult =
            mockMvc.post("/api/admin/foods$path") {
                adminHeaders(token())
                contentType = MediaType.APPLICATION_JSON
                body?.let { content = it }
            }.andReturn()

        fun json(r: MvcResult): Map<String, Any?> = objectMapper.readValue(r.response.contentAsString)

        @Suppress("UNCHECKED_CAST")
        fun payload(r: MvcResult) = json(r)["payload"] as Map<String, Any?>

        fun hasVector(foodId: Long, op: FoodVectorOutboxOperation) =
            vectorOutboxRepository.existsByFoodIdAndOperationAndOutboxStatus(foodId, op, FoodVectorOutboxStatus.PENDING)

        beforeContainer {
            vectorOutboxRepository.deleteAll()
            contentOutboxRepository.deleteAll()
            foodRepository.deleteAll()
        }

        given("삭제와 복구") {
            `when`("삭제 후 목록·복구를 거치면") {
                then("삭제 포함 조회에만 보이고 복구하면 돌아오며 벡터 요청이 예약된다") {
                    val food = seed("삭제복구음식")

                    mockMvc.delete("/api/admin/foods/${food.id}") { adminHeaders(token()) }.andExpect { status { isOk() } }
                    hasVector(food.id, FoodVectorOutboxOperation.DELETE) shouldBe true
                    mockMvc.delete("/api/admin/foods/${food.id}") { adminHeaders(token()) }.andExpect { status { isOk() } }

                    @Suppress("UNCHECKED_CAST")
                    fun listNames(q: String) = (payload(mockMvc.get("/api/admin/foods$q") { adminHeaders(token()) }.andReturn())["items"] as List<Map<String, Any?>>).map { it["displayName"] }
                    listNames("?q=${food.id}") shouldBe emptyList()
                    listNames("?q=${food.id}&includeDeleted=true") shouldBe listOf("삭제복구음식")

                    val restored = post("/${food.id}/restore")
                    restored.response.status shouldBe 200
                    payload(restored)["deleted"] shouldBe false
                    listNames("?q=${food.id}") shouldBe listOf("삭제복구음식")
                    hasVector(food.id, FoodVectorOutboxOperation.UPSERT) shouldBe true
                }
            }

            `when`("없는 음식을 복구하면") {
                then("400 FOOD-001") {
                    val result = post("/999999/restore")

                    result.response.status shouldBe 400
                    json(result)["code"] shouldBe "FOOD-001"
                }
            }
        }

        given("일괄 작업") {
            `when`("승인 3건 중 1건이 이미지가 없으면") {
                then("건별 결과가 오고 나머지는 READY 가 된다") {
                    val a = seed("일괄A", FoodContentStatus.PENDING_REVIEW)
                    val b = seed("일괄B", FoodContentStatus.PENDING_REVIEW, imageRef = null)
                    val c = seed("일괄C", FoodContentStatus.PENDING_REVIEW)

                    val result = payload(post("/bulk", """{"action":"APPROVE","ids":[${a.id},${b.id},${c.id}]}"""))

                    result["succeeded"] shouldBe 2
                    result["failed"] shouldBe 1
                    @Suppress("UNCHECKED_CAST")
                    val failed = (result["results"] as List<Map<String, Any?>>).single { it["ok"] == false }
                    failed["id"] shouldBe b.id.toInt()
                    failed["code"] shouldBe "FOOD-005"
                    foodRepository.findById(a.id).get().contentStatus shouldBe FoodContentStatus.READY
                    foodRepository.findById(b.id).get().contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                    foodRepository.findById(c.id).get().contentStatus shouldBe FoodContentStatus.READY
                }
            }

            `when`("501건을 보내면") {
                then("400") {
                    val ids = (1..501).joinToString(",")
                    post("/bulk", """{"action":"DELETE","ids":[$ids]}""").response.status shouldBe 400
                }
            }

            `when`("재수집을 일괄 요청하면") {
                then("각 음식에 수집 요청이 생긴다") {
                    val a = seed("일괄재수집A")
                    val b = seed("일괄재수집B")

                    payload(post("/bulk", """{"action":"RECOLLECT","ids":[${a.id},${b.id}]}"""))["succeeded"] shouldBe 2

                    contentOutboxRepository.count() shouldBe 2
                }
            }
        }

        given("시드 응답") {
            `when`("기존·삭제 충돌·신규 이름이 섞여 있으면") {
                then("생성 id·건너뛴 이름·삭제 충돌 이름이 분류된다") {
                    seed("기존음식")
                    val deleted = seed("삭제된음식")
                    mockMvc.delete("/api/admin/foods/${deleted.id}") { adminHeaders(token()) }.andExpect { status { isOk() } }

                    val result = payload(post("", """{"koreanNames":["기존음식","삭제된음식","완전새음식"]}"""))

                    result["requested"] shouldBe 3
                    result["created"] shouldBe 1
                    result["skipped"] shouldBe 2
                    (result["createdIds"] as List<*>).size shouldBe 1
                    result["skippedNames"] shouldBe listOf("기존음식")
                    result["blockedByDeletedNames"] shouldBe listOf("삭제된음식")
                    foodRepository.findByKoreanNameIn(setOf("완전새음식")).map { it.displayName } shouldContainExactlyInAnyOrder listOf("완전새음식")
                }
            }
        }
    }
}
