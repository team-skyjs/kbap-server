package com.kbap.app.api.admin

import com.kbap.app.api.foodimage.FakeFoodImageBatchClient
import com.kbap.application.auth.token.TokenIssuer
import com.kbap.core.error.ErrorCode
import com.kbap.core.testsupport.MySqlContainerConfig
import com.kbap.domain.food.FoodJpaRepository
import com.kbap.domain.food.ImageBatchItemJpaRepository
import com.kbap.domain.food.ImageBatchJpaRepository
import com.kbap.domain.food.model.Food
import com.kbap.domain.member.model.MemberRole
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
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
        val path = "/api/v1/admin/foods/images"

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

        given("관리자 이미지 일괄 제출 API") {
            `when`("이미지 없는 음식 2건이 있는 상태에서 제출하면") {
                then("200 + 배치/음식 카운트를 즉시 반환한다") {
                    foodRepository.save(Food.incomplete("제출김치찌개"))
                    foodRepository.save(Food.incomplete("제출된장찌개"))

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
