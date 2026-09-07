package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.kbap.api.food.FakeFoodImageBatchClient
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.ImageBatchJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.ImageBatch
import com.kbap.common.domain.food.model.ImageBatchItem
import com.kbap.common.domain.food.model.ImageBatchStatus
import com.kbap.common.domain.member.model.MemberRole
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@IntegrationTest
class AdminFoodImageControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var batchRepository: ImageBatchJpaRepository

    @Autowired
    private lateinit var itemRepository: ImageBatchItemJpaRepository

    @Autowired
    private lateinit var fakeClient: FakeFoodImageBatchClient

    init {
        val path = "/api/admin/foods/images"

        fun clearAll() {
            itemRepository.deleteAll()
            batchRepository.deleteAll()
            foodRepository.deleteAll()
            fakeClient.reset()
        }

        fun postSubmit(token: String? = tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)): ResultActionsDsl =
            mockMvc.post(path) {
                token?.let { header("Authorization", "Bearer $it") }
            }

        beforeContainer { clearAll() }
        afterSpec { clearAll() }

        given("관리자 이미지 배치 목록 API") {
            `when`("배치 이력이 있으면") {
                then("최근 배치를 항목 카운트와 함께 id 내림차순으로 내려준다") {
                    val batch = batchRepository.save(
                        ImageBatch(batchStatus = ImageBatchStatus.SUBMITTED, promptVersion = "v1", model = "gpt-image-2"),
                    )
                    val food = foodRepository.save(Food.failed("배치목록찌개"))
                    itemRepository.save(ImageBatchItem(batchId = batch.id, foodId = food.id))

                    mockMvc.get(path) { header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}") }
                        .andExpect {
                            status { isOk() }
                            jsonPath("$.payload.batches.length()") { value(1) }
                            jsonPath("$.payload.batches[0].id") { value(batch.id) }
                            jsonPath("$.payload.batches[0].batchStatus") { value("SUBMITTED") }
                            jsonPath("$.payload.batches[0].model") { value("gpt-image-2") }
                            jsonPath("$.payload.batches[0].pendingCount") { value(1) }
                            jsonPath("$.payload.batches[0].totalCount") { value(1) }
                            jsonPath("$.payload.batches[0].submittedAt") { exists() }
                        }
                }
            }

            `when`("배치가 없으면") {
                then("빈 목록으로 성공한다") {
                    mockMvc.get(path) { header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}") }
                        .andExpect {
                            status { isOk() }
                            jsonPath("$.payload.batches.length()") { value(0) }
                        }
                }
            }
        }

        given("관리자 이미지 일괄 제출 API") {
            `when`("이미지 없는 음식 2건이 있는 상태에서 제출하면") {
                then("200 + 배치/음식 카운트를 즉시 반환한다") {
                    foodRepository.save(Food.failed("제출김치찌개").apply { contentStatus = FoodContentStatus.PENDING_IMAGE })
                    foodRepository.save(Food.failed("제출된장찌개").apply { contentStatus = FoodContentStatus.PENDING_IMAGE })

                    postSubmit().andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.submittedBatchCount") { value(1) }
                        jsonPath("$.payload.submittedFoodCount") { value(2) }
                    }

                    fakeClient.submitted.single().size shouldBe 2
                }
            }

            `when`("후보가 0건이면") {
                then("0/0 으로 정상 응답한다") {
                    postSubmit().andExpect {
                        status { isOk() }
                        jsonPath("$.payload.submittedBatchCount") { value(0) }
                        jsonPath("$.payload.submittedFoodCount") { value(0) }
                    }
                }
            }
        }

        given("관리자 이미지 일괄 제출 — 인가") {
            `when`("토큰 없이 호출하면") {
                then("401 로 거절한다") {
                    postSubmit(token = null).andExpect { status { isUnauthorized() } }
                    batchRepository.count() shouldBe 0
                }
            }

            `when`("USER 역할 토큰으로 호출하면") {
                then("AUTH-008(403) 로 거절한다") {
                    postSubmit(token = tokenIssuer.issueAccessToken(1, MemberRole.USER))
                        .andExpect {
                            status { isForbidden() }
                            jsonPath("$.code") { value(ErrorCode.ADMIN_FORBIDDEN.code) }
                        }
                    batchRepository.count() shouldBe 0
                }
            }
        }
    }
}
