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
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
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
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminFoodPipelineControllerTest : BehaviorSpec() {
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

    @Autowired
    private lateinit var dataSource: DataSource

    private val objectMapper = jacksonObjectMapper()

    init {
        val targets = LanguageCode.entries.filter { it != LanguageCode.KO }.map { it.code }
        fun allTargets(v: String) = targets.associateWith { "$v-$it" }

        fun seed(name: String, status: FoodContentStatus = FoodContentStatus.READY): Food = foodRepository.save(
            Food(
                koreanName = name, displayName = name, imageRef = "images/food/$name.webp", description = "$name 설명", spiciness = 1,
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

        fun get(path: String): MvcResult = mockMvc.get("/api/admin/foods$path") { adminHeaders(token()) }.andReturn()

        fun json(r: MvcResult): Map<String, Any?> = objectMapper.readValue(r.response.contentAsString)

        @Suppress("UNCHECKED_CAST")
        fun payload(r: MvcResult) = json(r)["payload"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        fun items(r: MvcResult) = payload(r)["items"] as List<Map<String, Any?>>

        fun ageSentAt(outboxId: Long, hours: Long) {
            AdminTestTables.ageSentAt(dataSource, outboxId, hours)
        }

        beforeContainer {
            vectorOutboxRepository.deleteAll()
            contentOutboxRepository.deleteAll()
            foodRepository.deleteAll()
        }

        given("개별 재수집") {
            `when`("같은 음식에 두 번 요청하면") {
                then("첫 번째만 생성되고 두 번째는 기존 요청 id 를 돌려준다") {
                    val food = seed("재수집음식")

                    val first = payload(post("/${food.id}/recollect"))
                    val second = payload(post("/${food.id}/recollect"))

                    first["created"] shouldBe true
                    second["created"] shouldBe false
                    second["outboxId"] shouldBe first["outboxId"]
                    contentOutboxRepository.findByFoodIdInAndOutboxStatus(listOf(food.id), FoodContentOutboxStatus.PENDING).size shouldBe 1
                }
            }
        }

        given("콘텐츠 아웃박스") {
            `when`("발행 후 오래된 요청을 조회하면") {
                then("stuck 으로 표시되고 재발행·취소가 동작한다") {
                    val food = seed("고착음식")
                    val stuck = contentOutboxRepository.save(FoodContentOutbox.pending(food.id, "고착음식").apply { markSent() })
                    ageSentAt(stuck.id, 4)
                    val fresh = contentOutboxRepository.save(FoodContentOutbox.pending(food.id, "고착음식"))

                    val list = items(get("/content-outboxes?stuckHours=3"))
                    list.single { it["id"] == stuck.id.toInt() }["stuck"] shouldBe true
                    list.single { it["id"] == fresh.id.toInt() }["stuck"] shouldBe false
                    items(get("/content-outboxes?status=SENT")).size shouldBe 1

                    val requeued = post("/content-outboxes/${stuck.id}/requeue")
                    requeued.response.status shouldBe 200
                    contentOutboxRepository.findById(stuck.id).get().let {
                        it.outboxStatus shouldBe FoodContentOutboxStatus.PENDING
                        it.sentAt.shouldBeNull()
                    }

                    val again = post("/content-outboxes/${stuck.id}/requeue")
                    again.response.status shouldBe 409
                    json(again)["code"] shouldBe "FOOD-010"

                    post("/content-outboxes/${fresh.id}/cancel").response.status shouldBe 200
                    contentOutboxRepository.findById(fresh.id).get().outboxStatus shouldBe FoodContentOutboxStatus.CANCELED
                    post("/content-outboxes/${fresh.id}/cancel").response.status shouldBe 409
                }
            }

            `when`("취소된 요청으로 랭체인 적재 콜백이 오면") {
                then("400 으로 거절한다") {
                    val food = seed("취소콜백음식", FoodContentStatus.FAILED)
                    val outbox = contentOutboxRepository.save(FoodContentOutbox.pending(food.id, "취소콜백음식").apply { cancel() })
                    val body = objectMapper.writeValueAsString(
                        mapOf(
                            "outboxId" to outbox.id, "foodId" to food.id, "passed" to true,
                            "description" to "설명", "spiciness" to 1,
                            "nameTranslations" to allTargets("n"), "descriptionTranslations" to allTargets("d"),
                            "ingredients" to listOf(mapOf("code" to "SOY", "inclusion_percent" to 100)),
                        ),
                    )

                    post("/contents", body).response.status shouldBe 400
                    foodRepository.findById(food.id).get().contentStatus shouldBe FoodContentStatus.FAILED
                }
            }
        }

        given("벡터 아웃박스") {
            `when`("실패 건이 여러 개일 때 일괄 재시도하면") {
                then("전부 PENDING·attempts 0 이 되고 건수를 돌려준다") {
                    val food = seed("벡터음식")
                    repeat(3) { vectorOutboxRepository.save(FoodVectorOutbox.upsert(food.id).apply { failPermanently("boom") }) }

                    val result = payload(post("/vector-outboxes/retry-all-failed"))

                    result["retried"] shouldBe 3
                    vectorOutboxRepository.findAll().all { it.outboxStatus == FoodVectorOutboxStatus.PENDING && it.attempts == 0 } shouldBe true
                    items(get("/vector-outboxes?status=PENDING")).size shouldBe 3
                }
            }

            `when`("미적재 READY 음식을 적재 요청하면") {
                then("예약 수와 잔여 수를 돌려준다") {
                    seed("적재음식1")
                    seed("적재음식2")

                    val result = payload(post("/vector-outboxes/enqueue"))

                    result["enqueued"] shouldBe 2
                    result["remaining"] shouldBe 0
                }
            }

            `when`("FAILED 가 아닌 건을 단건 재시도하면") {
                then("409 FOOD-010") {
                    val food = seed("재시도불가음식")
                    val pending = vectorOutboxRepository.save(FoodVectorOutbox.upsert(food.id))

                    val result = post("/vector-outboxes/${pending.id}/retry")

                    result.response.status shouldBe 409
                    json(result)["code"] shouldBe "FOOD-010"
                }
            }
        }
    }
}
