package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.PATH
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.allTargets
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.passedBody
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class AdminFoodContentIngestControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var outboxRepository: FoodContentOutboxJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        val namePrefix = "적재테스트-"

        fun clearFoods(): Unit =
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute("DELETE FROM food_content_outbox")
                    it.execute("DELETE FROM image_batch_item")
                    it.execute("DELETE FROM image_batch")
                    it.execute("DELETE FROM food_vector_outbox")
                    it.execute("DELETE FROM food_image")
                    it.execute("DELETE FROM food")
                }
            }

        fun saveFood(rawName: String, contentStatus: FoodContentStatus, imageRef: String?): Food {
            val food = foodJpaRepository.save(
                Food(
                    koreanName = namePrefix + rawName,
                    displayName = namePrefix + rawName,
                    imageRef = imageRef,
                    description = Food.PLACEHOLDER_DESCRIPTION,
                    spiciness = Food.SPICINESS_UNASSESSED,
                    contentStatus = contentStatus,
                    ingredients = null,
                ),
            )
            outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
            return food
        }

        fun ingest(body: Map<String, Any?>): ResultActionsDsl {
            val foodId = body.getValue("foodId") as Long
            val outboxId = body["outboxId"] ?: outboxRepository
                .findByFoodIdInAndOutboxStatus(setOf(foodId), FoodContentOutboxStatus.PENDING)
                .singleOrNull()
                ?.id
                ?: foodJpaRepository.findById(foodId).orElse(null)?.let {
                    outboxRepository.save(FoodContentOutbox.pending(it.id, it.displayName)).id
                }
                ?: 999_999L
            return mockMvc.post(PATH) {
                header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(body + ("outboxId" to outboxId))
            }
        }

        fun reloaded(id: Long): Food = foodJpaRepository.findById(id).orElseThrow()

        given("성공 결과 적재") {
            `when`("이미 서비스 중이고 사진이 있는 음식이면") {
                then("텍스트만 갱신되고 상태·사진은 그대로다") {
                    clearFoods()
                    val food = saveFood("칼국수", FoodContentStatus.READY, "images/food/kalguksu.webp")

                    ingest(passedBody(food.id)).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }

                    val updated = reloaded(food.id)
                    updated.description shouldBe "들깨를 곱게 갈아 넣어 고소한 칼국수"
                    updated.spiciness shouldBe 2
                    updated.nameTranslations shouldBe allTargets("칼국수")
                    updated.ingredients?.map { it.code } shouldBe listOf("SESAME")
                    updated.contentStatus shouldBe FoodContentStatus.READY
                    updated.imageRef shouldBe "images/food/kalguksu.webp"
                }
            }

            `when`("관리자 확인 대상이고 사진이 이미 있으면") {
                then("승인 대기로 가고 사진은 재활용된다") {
                    clearFoods()
                    val food = saveFood("콩국수", FoodContentStatus.FAILED, "images/food/kongguksu.webp")

                    ingest(passedBody(food.id)).andExpect { status { isOk() } }

                    val updated = reloaded(food.id)
                    updated.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                    updated.imageRef shouldBe "images/food/kongguksu.webp"
                }
            }

            `when`("사진이 없는 음식이면") {
                then("이미지 생성 대기가 된다") {
                    clearFoods()
                    val food = saveFood("잔치국수", FoodContentStatus.FAILED, null)

                    ingest(passedBody(food.id)).andExpect { status { isOk() } }

                    reloaded(food.id).contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
                }
            }

            `when`("벡터 메타데이터용 긴 설명이 함께 오면") {
                then("긴 설명이 저장된다") {
                    clearFoods()
                    val food = saveFood("들깨수제비", FoodContentStatus.FAILED, null)

                    ingest(passedBody(food.id, longDescription = "들깨" .repeat(400))).andExpect { status { isOk() } }

                    reloaded(food.id).longDescription shouldBe "들깨".repeat(400)
                }
            }

            `when`("긴 설명 없이 재적재되면") {
                then("이전 긴 설명은 지워진다") {
                    clearFoods()
                    val food = saveFood("김치수제비", FoodContentStatus.FAILED, null)
                    ingest(passedBody(food.id, longDescription = "긴 설명")).andExpect { status { isOk() } }

                    ingest(passedBody(food.id)).andExpect { status { isOk() } }

                    reloaded(food.id).longDescription.shouldBeNull()
                }
            }

            `when`("직전 실패 기록이 있는 음식이면") {
                then("실패 유형과 사유가 지워진다") {
                    clearFoods()
                    val food = saveFood("비빔국수", FoodContentStatus.FAILED, null)
                    ingest(AdminFoodContentIngestTestSupport.failedBody(food.id)).andExpect { status { isOk() } }

                    ingest(passedBody(food.id)).andExpect { status { isOk() } }

                    val updated = reloaded(food.id)
                    updated.contentFailureKind.shouldBeNull()
                    updated.contentReviewRejectionReason.shouldBeNull()
                }
            }

            `when`("같은 결과가 두 번 도착하면") {
                then("두 번째 요청은 이미 처리된 계약 응답으로 거절한다") {
                    clearFoods()
                    val food = saveFood("메밀국수", FoodContentStatus.FAILED, null)
                    val outbox = outboxRepository
                        .findByFoodIdInAndOutboxStatus(setOf(food.id), FoodContentOutboxStatus.PENDING)
                        .single()

                    ingest(passedBody(food.id, outbox.id)).andExpect { status { isOk() } }
                    ingest(
                        passedBody(
                            food.id,
                            outbox.id,
                            description = "중복 요청이 덮어쓰면 안 되는 설명",
                        ),
                    ).andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("FOOD-004") }
                    }

                    val updated = reloaded(food.id)
                    updated.contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
                    updated.description shouldBe "들깨를 곱게 갈아 넣어 고소한 칼국수"
                    outboxRepository.findById(outbox.id).orElseThrow().outboxStatus shouldBe
                        FoodContentOutboxStatus.COMPLETE
                }
            }

            `when`("재료가 빈 배열이면") {
                then("조사 완료·해당 없음으로 저장한다") {
                    clearFoods()
                    val food = saveFood("우동", FoodContentStatus.FAILED, null)

                    ingest(passedBody(food.id, ingredients = emptyList())).andExpect { status { isOk() } }

                    reloaded(food.id).ingredients shouldBe emptyList()
                }
            }
        }
    }
}
