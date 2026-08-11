package com.kbap.api.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.PATH
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.allTargets
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.failedBody
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.passedBody
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.testsupport.MySqlContainerConfig
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
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminFoodContentIngestValidationTest : BehaviorSpec() {
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
        val namePrefix = "적재검증-"

        fun clearFoods(): Unit =
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute("DELETE FROM food_content_outbox")
                    it.execute("DELETE FROM image_batch_item")
                    it.execute("DELETE FROM image_batch")
                    it.execute("DELETE FROM food")
                }
            }

        fun saveFood(rawName: String): Food {
            val food = foodJpaRepository.save(
                Food(
                    koreanName = namePrefix + rawName,
                    displayName = namePrefix + rawName,
                    description = Food.PLACEHOLDER_DESCRIPTION,
                    spiciness = Food.SPICINESS_UNASSESSED,
                    contentStatus = FoodContentStatus.FAILED,
                    ingredients = null,
                ),
            )
            outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
            return food
        }

        fun ingest(
            body: Map<String, Any?>,
            token: String? = tokenIssuer.issueAccessToken(0, MemberRole.ADMIN),
            fillOutboxId: Boolean = true,
        ): ResultActionsDsl {
            val foodId = body.getValue("foodId") as Long
            val outboxId = body["outboxId"] ?: outboxRepository
                .findByFoodIdInAndOutboxStatus(setOf(foodId), FoodContentOutboxStatus.PENDING)
                .singleOrNull()
                ?.id
                ?: 999_999L
            val requestBody = if (fillOutboxId) body + ("outboxId" to outboxId) else body - "outboxId"
            return mockMvc.post(PATH) {
                token?.let { header("Authorization", "Bearer $it") }
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(requestBody)
            }
        }

        fun reloaded(id: Long): Food = foodJpaRepository.findById(id).orElseThrow()

        given("계약 위반 요청") {
            `when`("outboxId가 없으면") {
                then("저장하지 않고 거절한다") {
                    clearFoods()
                    val food = saveFood("아웃박스없는국수")

                    ingest(passedBody(food.id), fillOutboxId = false).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                    }

                    reloaded(food.id).description shouldBe Food.PLACEHOLDER_DESCRIPTION
                }
            }

            `when`("outboxId와 foodId 조합이 맞지 않으면") {
                then("저장하지 않고 거절한다") {
                    clearFoods()
                    val first = saveFood("첫국수")
                    val second = saveFood("둘국수")
                    val firstOutbox = outboxRepository
                        .findByFoodIdInAndOutboxStatus(setOf(first.id), FoodContentOutboxStatus.PENDING)
                        .single()

                    ingest(passedBody(second.id, firstOutbox.id)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                    }

                    reloaded(second.id).description shouldBe Food.PLACEHOLDER_DESCRIPTION
                }
            }

            `when`("번역이 9개 언어를 다 채우지 않으면") {
                then("저장하지 않고 거절한다") {
                    clearFoods()
                    val food = saveFood("칼국수")
                    val partial = allTargets("칼국수") - "th"

                    ingest(passedBody(food.id, nameTranslations = partial)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                    }

                    reloaded(food.id).description shouldBe Food.PLACEHOLDER_DESCRIPTION
                }
            }

            `when`("긴 설명이 1000자를 넘으면") {
                then("거절한다") {
                    clearFoods()
                    val food = saveFood("들깨국수")

                    ingest(passedBody(food.id, longDescription = "가".repeat(1001))).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                    }

                    reloaded(food.id).description shouldBe Food.PLACEHOLDER_DESCRIPTION
                }
            }

            `when`("번역 값이 비어 있으면") {
                then("거절한다") {
                    clearFoods()
                    val food = saveFood("콩국수")
                    val blank = allTargets("콩국수") + ("ja" to " ")

                    ingest(passedBody(food.id, descriptionTranslations = blank)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                    }
                }
            }

            `when`("재료가 누락되면") {
                then("거절한다 — 미조사와 조사 완료를 구분해야 한다") {
                    clearFoods()
                    val food = saveFood("잔치국수")

                    ingest(passedBody(food.id) - "ingredients").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                    }
                }
            }

            `when`("재료 코드가 성분 카탈로그에 없으면") {
                then("거절한다 — 저장되면 음식 상세 조회가 코드 변환에서 터진다") {
                    clearFoods()
                    val food = saveFood("들깨칼국수")

                    ingest(
                        passedBody(
                            food.id,
                            ingredients = listOf(mapOf("code" to "PERILLA", "inclusion_percent" to 100)),
                        ),
                    ).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                    }
                }
            }

            `when`("맵기가 범위를 벗어나면") {
                then("거절한다") {
                    clearFoods()
                    val food = saveFood("메밀국수")

                    ingest(passedBody(food.id, spiciness = 11)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                    }
                }
            }

            `when`("설명이 비어 있으면") {
                then("거절한다") {
                    clearFoods()
                    val food = saveFood("우동")

                    ingest(passedBody(food.id, description = " ")).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                    }
                }
            }

            `when`("실패 유형이 정의된 3값 밖이면") {
                then("거절한다") {
                    clearFoods()
                    val food = saveFood("비빔국수")

                    ingest(failedBody(food.id, failureKind = "UNKNOWN_KIND")).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                    }
                }
            }

            `when`("실패인데 사유가 없으면") {
                then("거절한다") {
                    clearFoods()
                    val food = saveFood("막국수")

                    ingest(failedBody(food.id, reason = " ")).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                    }
                }
            }
        }

        given("대상 음식을 찾을 수 없는 요청") {
            `when`("아웃박스가 없는 foodId 이면") {
                then("계약 위반으로 거절한다") {
                    clearFoods()

                    ingest(passedBody(999_999)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                    }
                }
            }

            `when`("삭제된 음식이면") {
                then("되살리지 않고 거절한다 — 관리자의 삭제 의도를 자동 호출이 뒤집지 않는다") {
                    clearFoods()
                    val food = saveFood("삭제국수")
                    val outbox = outboxRepository
                        .findByFoodIdInAndOutboxStatus(setOf(food.id), FoodContentOutboxStatus.PENDING)
                        .single()
                    food.delete()
                    foodJpaRepository.save(food)

                    ingest(passedBody(food.id)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.FOOD_NOT_FOUND.code) }
                    }
                    outboxRepository.findById(outbox.id).orElseThrow().outboxStatus shouldBe
                        FoodContentOutboxStatus.PENDING
                }
            }
        }

        given("인증") {
            `when`("관리자 토큰이 아니면") {
                then("거절한다") {
                    clearFoods()
                    val food = saveFood("일반국수")

                    ingest(passedBody(food.id), token = tokenIssuer.issueAccessToken(1, MemberRole.USER)).andExpect {
                        status { isForbidden() }
                        jsonPath("$.code") { value(ErrorCode.ADMIN_FORBIDDEN.code) }
                    }
                }
            }
        }
    }
}
