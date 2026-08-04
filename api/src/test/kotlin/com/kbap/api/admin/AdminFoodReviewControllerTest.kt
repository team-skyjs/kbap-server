package com.kbap.api.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodAvoidanceItem
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminFoodReviewControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        val path = "/api/v1/admin/foods/reviews"
        val targetLangs = LanguageCode.entries.filter { it != LanguageCode.KO }.map { it.code }

        fun allTargets(value: String) = targetLangs.associateWith { "$value-$it" }

        // 음식명은 unique 라 스펙이 끝나고 남는 행이 다른 스펙의 고정 시드와 충돌한다 — 접두로 이름 공간을 분리한다.
        val namePrefix = "검수테스트-"

        fun clearFoods(): Unit =
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute("DELETE FROM image_batch_item")
                    it.execute("DELETE FROM image_batch")
                    it.execute("DELETE FROM food")
                }
            }

        fun saveFood(
            rawName: String,
            contentStatus: FoodContentStatus = FoodContentStatus.PENDING_REVIEW,
            reviewAttempts: Int = 0,
        ): Food = foodJpaRepository.save(
            Food(
                koreanName = namePrefix + rawName,
                imageRef = "images/food/$rawName.webp",
                description = "구수한 $rawName",
                spiciness = 3,
                nameTranslations = allTargets(rawName),
                descriptionTranslations = allTargets("stew"),
                avoidanceSubstances = listOf(FoodAvoidanceItem("SOYBEAN", 100)),
                contentStatus = contentStatus,
                reviewAttempts = reviewAttempts,
            ),
        )

        fun adminToken(): String = tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)

        fun getTargets(query: String = "", token: String? = adminToken()): ResultActionsDsl =
            mockMvc.get("$path$query") { token?.let { header("Authorization", "Bearer $it") } }

        fun postResult(foodId: Long, body: Map<String, Any?>, token: String? = adminToken()): ResultActionsDsl =
            mockMvc.post("$path/$foodId") {
                token?.let { header("Authorization", "Bearer $it") }
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(body)
            }

        fun reloaded(id: Long): Food = foodJpaRepository.findById(id).orElseThrow()

        given("검수 대상 조회 API") {
            `when`("PENDING_REVIEW 음식과 다른 상태 음식이 섞여 있으면") {
                then("PENDING_REVIEW 건만 콘텐츠 필드와 함께 반환한다") {
                    clearFoods()
                    val target = saveFood("된장찌개")
                    saveFood("김치찌개", FoodContentStatus.READY)
                    saveFood("순두부", FoodContentStatus.INCOMPLETE)

                    getTargets().andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].foodId") { value(target.id) }
                        jsonPath("$.payload.items[0].koreanName") { value("검수테스트-된장찌개") }
                        jsonPath("$.payload.items[0].description") { value("구수한 된장찌개") }
                        jsonPath("$.payload.items[0].spiciness") { value(3) }
                        jsonPath("$.payload.items[0].reviewAttempts") { value(0) }
                        jsonPath("$.payload.items[0].avoidanceSubstances[0].code") { value("SOYBEAN") }
                        jsonPath("$.payload.items[0].nameTranslations.en") { value("된장찌개-en") }
                        jsonPath("$.payload.items[0].imageUrl") { exists() }
                    }
                }
            }

            `when`("검수 대상이 없으면") {
                then("빈 목록으로 성공한다") {
                    clearFoods()

                    getTargets().andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(0) }
                    }
                }
            }

            `when`("limit 을 지정하면") {
                then("그 건수까지만 반환한다") {
                    clearFoods()
                    saveFood("된장찌개")
                    saveFood("김치찌개")
                    saveFood("순두부찌개")

                    getTargets("?limit=2").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(2) }
                    }
                }
            }

            `when`("limit 이 허용 범위를 벗어나면") {
                then("400 으로 거절한다") {
                    clearFoods()

                    getTargets("?limit=0").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                    }
                }
            }

            `when`("ADMIN 이 아닌 토큰이면") {
                then("403 으로 거절한다") {
                    getTargets(token = tokenIssuer.issueAccessToken(1, MemberRole.USER)).andExpect {
                        status { isForbidden() }
                        jsonPath("$.code") { value(ErrorCode.ADMIN_FORBIDDEN.code) }
                    }
                }
            }
        }

        given("검수 결과 반영 API — 통과") {
            `when`("PENDING_REVIEW 음식이 통과하면") {
                then("REVIEWED 로 전이하고 콘텐츠·시도 횟수는 그대로다") {
                    clearFoods()
                    val food = saveFood("된장찌개")

                    postResult(food.id, mapOf("passed" to true)).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.contentStatus") { value("REVIEWED") }
                        jsonPath("$.payload.reviewAttempts") { value(0) }
                    }

                    val saved = reloaded(food.id)
                    saved.contentStatus shouldBe FoodContentStatus.REVIEWED
                    saved.description shouldBe "구수한 된장찌개"
                }
            }

            `when`("존재하지 않는 음식이면") {
                then("FOOD-001 로 거절한다") {
                    postResult(99_999_999L, mapOf("passed" to true)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.FOOD_NOT_FOUND.code) }
                    }
                }
            }

            `when`("passed 가 누락되면") {
                then("400 으로 거절한다") {
                    clearFoods()
                    val food = saveFood("된장찌개")

                    postResult(food.id, mapOf("reason" to "몰라")).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                    }
                }
            }
        }

        given("검수 결과 반영 API — 탈락") {
            `when`("재시도 여력이 남은 음식이 설명 문제로 탈락하면") {
                then("설명만 비우고 INCOMPLETE 로 롤백하며 시도 횟수를 올린다") {
                    clearFoods()
                    val food = saveFood("된장찌개")

                    postResult(
                        food.id,
                        mapOf(
                            "passed" to false,
                            "rejectedFields" to listOf("DESCRIPTION"),
                            "reason" to "설명이 음식과 무관함",
                        ),
                    ).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.contentStatus") { value("INCOMPLETE") }
                        jsonPath("$.payload.reviewAttempts") { value(1) }
                        jsonPath("$.payload.reviewRejectionReason") { doesNotExist() }
                    }

                    val saved = reloaded(food.id)
                    saved.needsDescription() shouldBe true
                    saved.nameTranslations.keys.sorted() shouldContainExactly targetLangs.sorted()
                    saved.imageRef shouldBe "images/food/된장찌개.webp"
                }
            }

            `when`("재시도를 모두 쓴 음식이 탈락하면") {
                then("콘텐츠를 유지한 채 REVIEW_REJECTED 로 전이하고 사유를 저장한다") {
                    clearFoods()
                    val food = saveFood("된장찌개", reviewAttempts = Food.MAX_REVIEW_ATTEMPTS)

                    postResult(
                        food.id,
                        mapOf(
                            "passed" to false,
                            "rejectedFields" to listOf("DESCRIPTION"),
                            "reason" to "설명이 여전히 부정확함",
                        ),
                    ).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.contentStatus") { value("REVIEW_REJECTED") }
                        jsonPath("$.payload.reviewRejectionReason") { value("설명이 여전히 부정확함") }
                    }

                    reloaded(food.id).description shouldBe "구수한 된장찌개"
                }
            }

            `when`("탈락인데 문제 필드가 비어 있으면") {
                then("400 으로 거절한다") {
                    clearFoods()
                    val food = saveFood("된장찌개")

                    postResult(food.id, mapOf("passed" to false, "reason" to "그냥 별로")).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                    }
                }
            }

            `when`("알 수 없는 문제 필드가 오면") {
                then("400 으로 거절한다") {
                    clearFoods()
                    val food = saveFood("된장찌개")

                    postResult(
                        food.id,
                        mapOf("passed" to false, "rejectedFields" to listOf("UNKNOWN_FIELD")),
                    ).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                    }
                }
            }

            `when`("검수 대상이 아닌 음식에 결과가 도착하면") {
                then("400 으로 거절한다") {
                    clearFoods()
                    val food = saveFood("된장찌개", FoodContentStatus.INCOMPLETE)

                    postResult(
                        food.id,
                        mapOf("passed" to false, "rejectedFields" to listOf("DESCRIPTION")),
                    ).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                    }
                }
            }
        }
    }
}
