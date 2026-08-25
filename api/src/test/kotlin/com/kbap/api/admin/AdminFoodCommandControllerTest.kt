package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.admin.AdminTestTokens.adminHeaders
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.admin.AdminAuditLogJpaRepository
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
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
class AdminFoodCommandControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var vectorOutboxRepository: FoodVectorOutboxJpaRepository

    @Autowired
    private lateinit var auditLogRepository: AdminAuditLogJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val objectMapper = jacksonObjectMapper()

    init {
        val targets = LanguageCode.entries.filter { it != LanguageCode.KO }.map { it.code }
        fun allTargets(v: String) = targets.associateWith { "$v-$it" }

        fun seedFood(
            name: String,
            status: FoodContentStatus,
            imageRef: String? = "images/food/$name.webp",
            ingredients: List<FoodIngredient>? = listOf(FoodIngredient("SOY", 100)),
        ): Food = foodRepository.save(
            Food(
                koreanName = name,
                displayName = name,
                imageRef = imageRef,
                description = "$name 설명",
                spiciness = 2,
                nameTranslations = allTargets(name),
                descriptionTranslations = allTargets("$name-desc"),
                ingredients = ingredients,
                contentStatus = status,
            ),
        )

        fun updateBody(version: Long, overrides: Map<String, Any?> = emptyMap()): String {
            val base = mutableMapOf<String, Any?>(
                "version" to version,
                "koreanName" to "된장찌개",
                "description" to "새 설명",
                "longDescription" to null,
                "spiciness" to 4,
                "imageRef" to "images/food/updated.webp",
                "nameTranslations" to allTargets("n"),
                "descriptionTranslations" to allTargets("d"),
                "ingredients" to listOf(mapOf("code" to "SOY", "inclusionPercent" to 90)),
            )
            base.putAll(overrides)
            return objectMapper.writeValueAsString(base)
        }

        fun token() = AdminTestTokens.adminAccessToken(tokenIssuer, 1L)

        fun put(id: Long, body: String, token: String = token()): MvcResult =
            mockMvc.put("/api/admin/foods/$id") {
                adminHeaders(token)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()

        fun post(path: String, body: String? = null, token: String = token()): MvcResult =
            mockMvc.post("/api/admin/foods$path") {
                adminHeaders(token)
                contentType = MediaType.APPLICATION_JSON
                body?.let { content = it }
            }.andReturn()

        fun json(result: MvcResult): Map<String, Any?> = objectMapper.readValue(result.response.contentAsString)

        @Suppress("UNCHECKED_CAST")
        fun payload(result: MvcResult) = json(result)["payload"] as Map<String, Any?>

        fun hasVector(foodId: Long, op: FoodVectorOutboxOperation) =
            vectorOutboxRepository.existsByFoodIdAndOperationAndOutboxStatus(foodId, op, FoodVectorOutboxStatus.PENDING)

        beforeContainer {
            auditLogRepository.deleteAll()
            vectorOutboxRepository.deleteAll()
            foodRepository.deleteAll()
        }

        given("PUT /api/admin/foods/{id}") {
            `when`("검증을 통과한 수정이면") {
                then("저장되고 상세 응답·감사(변경 필드만)·READY 면 벡터 UPSERT 가 남는다") {
                    val food = seedFood("된장찌개", FoodContentStatus.READY)

                    val result = put(food.id, updateBody(food.version))

                    result.response.status shouldBe 200
                    val p = payload(result)
                    p["description"] shouldBe "새 설명"
                    p["spiciness"] shouldBe 4
                    p["version"] shouldBe (food.version + 1).toInt()
                    @Suppress("UNCHECKED_CAST")
                    ((p["contentStatus"] as Map<String, Any?>)["code"]) shouldBe "READY"
                    hasVector(food.id, FoodVectorOutboxOperation.UPSERT) shouldBe true

                    val log = auditLogRepository.findAll().single { it.action == AdminAuditAction.FOOD_UPDATE }
                    log.targetId shouldBe food.id
                    log.adminAccountId shouldBe 1L
                    log.beforeJson!!.keys shouldContainExactlyInAnyOrder listOf("description", "spiciness", "imageRef", "nameTranslations", "descriptionTranslations", "ingredients")
                    log.afterJson!!["description"] shouldBe "새 설명"
                }
            }

            `when`("version 이 현재와 다르면") {
                then("409 COMMON-004 와 currentVersion 을 돌려주고 바뀌지 않는다") {
                    val food = seedFood("김치찌개", FoodContentStatus.READY)

                    val result = put(food.id, updateBody(food.version + 5))

                    result.response.status shouldBe 409
                    json(result)["code"] shouldBe "COMMON-004"
                    @Suppress("UNCHECKED_CAST")
                    (json(result)["payload"] as Map<String, Any?>)["currentVersion"] shouldBe food.version.toInt()
                    foodRepository.findById(food.id).get().description shouldBe "김치찌개 설명"
                }
            }

            `when`("contentStatus 필드를 포함하면") {
                then("400 으로 거절한다") {
                    val food = seedFood("비빔밥", FoodContentStatus.READY)

                    put(food.id, updateBody(food.version, mapOf("contentStatus" to "FAILED"))).response.status shouldBe 400
                }
            }

            `when`("카탈로그에 없는 재료·비율 0·번역 누락이면") {
                then("400 FOOD-006 과 필드별 오류를 돌려준다") {
                    val food = seedFood("잡채", FoodContentStatus.READY)

                    val bad = put(
                        food.id,
                        updateBody(
                            food.version,
                            mapOf(
                                "ingredients" to listOf(mapOf("code" to "PEANUTS", "inclusionPercent" to 0)),
                                "nameTranslations" to allTargets("n") - "ja",
                            ),
                        ),
                    )

                    bad.response.status shouldBe 400
                    json(bad)["code"] shouldBe "FOOD-006"
                    @Suppress("UNCHECKED_CAST")
                    val errors = (json(bad)["payload"] as Map<String, Any?>)["errors"] as List<Map<String, Any?>>
                    errors.map { it["field"] } shouldContainExactlyInAnyOrder
                        listOf("ingredients[0].code", "ingredients[0].inclusionPercent", "nameTranslations.ja")
                }
            }

            `when`("다른 음식과 같은 이름으로 바꾸면") {
                then("409 FOOD-007") {
                    seedFood("불고기", FoodContentStatus.READY)
                    val food = seedFood("제육볶음", FoodContentStatus.READY)

                    val result = put(food.id, updateBody(food.version, mapOf("koreanName" to "불 고기")))

                    result.response.status shouldBe 409
                    json(result)["code"] shouldBe "FOOD-007"
                }
            }

            `when`("회원 토큰이면") {
                then("403") {
                    val food = seedFood("떡볶이", FoodContentStatus.READY)
                    put(food.id, updateBody(food.version), AdminTestTokens.userAccessToken(tokenIssuer, 1L)).response.status shouldBe 403
                }
            }
        }

        given("승인·반려") {
            `when`("이미지·재료가 갖춰진 승인 대기 음식을 승인하면") {
                then("READY + 벡터 UPSERT + 감사 FOOD_APPROVE") {
                    val food = seedFood("삼계탕", FoodContentStatus.PENDING_REVIEW)

                    val result = post("/${food.id}/approve")

                    result.response.status shouldBe 200
                    @Suppress("UNCHECKED_CAST")
                    ((payload(result)["contentStatus"] as Map<String, Any?>)["code"]) shouldBe "READY"
                    payload(result)["allowedTransitions"] shouldBe listOf("UNPUBLISH")
                    hasVector(food.id, FoodVectorOutboxOperation.UPSERT) shouldBe true
                    auditLogRepository.findAll().single().action shouldBe AdminAuditAction.FOOD_APPROVE
                }
            }

            `when`("이미지가 없는 음식을 승인하면") {
                then("409 FOOD-005 reason NO_IMAGE") {
                    val food = seedFood("갈비탕", FoodContentStatus.PENDING_REVIEW, imageRef = null)

                    val result = post("/${food.id}/approve")

                    result.response.status shouldBe 409
                    json(result)["code"] shouldBe "FOOD-005"
                    @Suppress("UNCHECKED_CAST")
                    (json(result)["payload"] as Map<String, Any?>)["reason"] shouldBe "NO_IMAGE"
                }
            }

            `when`("이미 READY 인 음식을 승인하면") {
                then("200 멱등") {
                    val food = seedFood("냉면", FoodContentStatus.READY)
                    post("/${food.id}/approve").response.status shouldBe 200
                }
            }

            `when`("사유 없이 반려하면") {
                then("400") {
                    val food = seedFood("순대", FoodContentStatus.PENDING_REVIEW)
                    post("/${food.id}/reject", """{"reason":" "}""").response.status shouldBe 400
                }
            }

            `when`("사유와 함께 반려하면") {
                then("FAILED, 횟수 +1, 사유 기록, 감사 note") {
                    val food = seedFood("육개장", FoodContentStatus.PENDING_REVIEW)

                    val result = post("/${food.id}/reject", """{"reason":"사진이 음식이 아님"}""")

                    result.response.status shouldBe 200
                    val saved = foodRepository.findById(food.id).get()
                    saved.contentStatus shouldBe FoodContentStatus.FAILED
                    saved.contentReviewAttempts shouldBe 1
                    saved.contentReviewRejectionReason shouldBe "사진이 음식이 아님"
                    auditLogRepository.findAll().single().note shouldBe "사진이 음식이 아님"
                }
            }
        }

        given("전이") {
            `when`("FAILED 음식을 RESUBMIT 하면") {
                then("이미지 유무에 따라 분기하고 실패 필드가 비워진다") {
                    val withImage = seedFood("감자탕", FoodContentStatus.FAILED).apply {
                        contentFailureKind = FoodContentFailureKind.JUDGE_REJECTED
                        contentReviewRejectionReason = "이전"
                    }.let { foodRepository.save(it) }
                    val noImage = seedFood("설렁탕", FoodContentStatus.FAILED, imageRef = null)

                    post("/${withImage.id}/transitions", """{"transition":"RESUBMIT"}""").response.status shouldBe 200
                    post("/${noImage.id}/transitions", """{"transition":"RESUBMIT"}""").response.status shouldBe 200

                    foodRepository.findById(withImage.id).get().let {
                        it.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                        it.contentFailureKind.shouldBeNull()
                        it.contentReviewRejectionReason.shouldBeNull()
                    }
                    foodRepository.findById(noImage.id).get().contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
                }
            }

            `when`("READY 음식을 UNPUBLISH 하면") {
                then("PENDING_REVIEW + 벡터 DELETE") {
                    val food = seedFood("칼국수", FoodContentStatus.READY)

                    post("/${food.id}/transitions", """{"transition":"UNPUBLISH"}""").response.status shouldBe 200

                    foodRepository.findById(food.id).get().contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                    hasVector(food.id, FoodVectorOutboxOperation.DELETE) shouldBe true
                }
            }

            `when`("허용되지 않는 전이를 요청하면") {
                then("409 FOOD-005 와 allowed 목록") {
                    val food = seedFood("보쌈", FoodContentStatus.READY)

                    val result = post("/${food.id}/transitions", """{"transition":"APPROVE"}""")

                    result.response.status shouldBe 409
                    @Suppress("UNCHECKED_CAST")
                    (json(result)["payload"] as Map<String, Any?>)["allowed"] shouldBe listOf("UNPUBLISH")
                }
            }
        }
    }
}
