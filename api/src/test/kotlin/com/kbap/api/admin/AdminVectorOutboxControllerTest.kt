package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class AdminVectorOutboxControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var vectorOutboxRepository: FoodVectorOutboxJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val path = "/api/admin/foods/vector-outboxes"

        fun tokenOf(role: MemberRole): String = tokenIssuer.issueAccessToken(0, role)

        fun getPage(query: String = "", token: String? = tokenOf(MemberRole.ADMIN)): ResultActionsDsl =
            mockMvc.get("$path$query") { token?.let { header("Authorization", "Bearer $it") } }

        fun postEnqueue(): ResultActionsDsl =
            mockMvc.post("$path/enqueue") { header("Authorization", "Bearer ${tokenOf(MemberRole.ADMIN)}") }

        fun postRetry(id: Long): ResultActionsDsl =
            mockMvc.post("$path/$id/retry") { header("Authorization", "Bearer ${tokenOf(MemberRole.ADMIN)}") }

        fun saveFood(koreanName: String, contentStatus: FoodContentStatus = FoodContentStatus.READY): Food =
            foodJpaRepository.save(
                Food(koreanName = koreanName, description = "구수한 $koreanName", contentStatus = contentStatus),
            )

        fun saveOutbox(
            foodId: Long,
            outboxStatus: FoodVectorOutboxStatus = FoodVectorOutboxStatus.PENDING,
            operation: FoodVectorOutboxOperation = FoodVectorOutboxOperation.UPSERT,
            lastError: String? = null,
        ): FoodVectorOutbox = vectorOutboxRepository.save(
            FoodVectorOutbox(foodId = foodId, operation = operation, outboxStatus = outboxStatus)
                .apply { this.lastError = lastError },
        )

        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("벡터 아웃박스 목록 조회 API") {
            `when`("여러 상태의 아웃박스가 있으면") {
                then("id 내림차순 목록과 전체 건수·음식 이름을 내려준다") {
                    val food = saveFood("된장찌개")
                    saveOutbox(food.id)
                    val last = saveOutbox(food.id, FoodVectorOutboxStatus.FAILED, lastError = "embedding timeout")

                    getPage().andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.items.length()") { value(2) }
                        jsonPath("$.payload.items[0].id") { value(last.id) }
                        jsonPath("$.payload.items[0].foodId") { value(food.id) }
                        jsonPath("$.payload.items[0].displayName") { value("된장찌개") }
                        jsonPath("$.payload.items[0].operation") { value("UPSERT") }
                        jsonPath("$.payload.items[0].outboxStatus") { value("FAILED") }
                        jsonPath("$.payload.items[0].lastError") { value("embedding timeout") }
                        jsonPath("$.payload.items[0].attempts") { value(0) }
                        jsonPath("$.payload.items[0].createdAt") { exists() }
                        jsonPath("$.payload.totalCount") { value(2) }
                        jsonPath("$.payload.page") { value(1) }
                        jsonPath("$.payload.totalPages") { value(1) }
                        jsonPath("$.payload.hasPrev") { value(false) }
                        jsonPath("$.payload.hasNext") { value(false) }
                    }
                }
            }

            `when`("status 필터를 주면") {
                then("해당 상태 건만 전체 건수와 함께 내려준다") {
                    val food = saveFood("김치찌개")
                    saveOutbox(food.id)
                    val failed = saveOutbox(food.id, FoodVectorOutboxStatus.FAILED)
                    saveOutbox(food.id, FoodVectorOutboxStatus.COMPLETE)

                    getPage("?status=FAILED").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].id") { value(failed.id) }
                        jsonPath("$.payload.totalCount") { value(1) }
                    }
                }
            }

            `when`("액세스 토큰 없이 호출하면") {
                then("401 로 거절한다") {
                    getPage(token = null).andExpect { status { isUnauthorized() } }
                }
            }

            `when`("USER 역할 토큰으로 호출하면") {
                then("403(AUTH-008) 으로 거절한다") {
                    getPage(token = tokenOf(MemberRole.USER)).andExpect {
                        status { isForbidden() }
                        jsonPath("$.code") { value("AUTH-008") }
                    }
                }
            }
        }

        given("벡터 동기화 일괄 enqueue API") {
            `when`("UPSERT 아웃박스가 없는 READY 음식이 있으면") {
                then("그 수만큼 enqueue 하고 건수를 내려준다") {
                    saveFood("된장찌개")
                    saveFood("김치찌개")
                    saveFood("미완성찌개", FoodContentStatus.PENDING_REVIEW)

                    postEnqueue().andExpect {
                        status { isOk() }
                        jsonPath("$.payload.enqueued") { value(2) }
                    }
                }
            }

            `when`("같은 요청을 다시 보내면") {
                then("대상이 없어 0 건으로 멱등하다") {
                    saveFood("된장찌개")
                    postEnqueue().andExpect { jsonPath("$.payload.enqueued") { value(1) } }

                    postEnqueue().andExpect {
                        status { isOk() }
                        jsonPath("$.payload.enqueued") { value(0) }
                    }
                }
            }
        }

        given("벡터 아웃박스 재시도 API") {
            `when`("FAILED 건을 재시도하면") {
                then("PENDING 으로 되돌리고 retried=true 를 내려준다") {
                    val food = saveFood("실패찌개")
                    val outbox = saveOutbox(food.id, FoodVectorOutboxStatus.FAILED, lastError = "timeout")

                    postRetry(outbox.id).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.retried") { value(true) }
                        jsonPath("$.payload.outboxStatus") { value("PENDING") }
                    }

                    vectorOutboxRepository.findById(outbox.id).orElseThrow().outboxStatus shouldBe
                        FoodVectorOutboxStatus.PENDING
                }
            }

            `when`("이미 PENDING 인 건을 재시도하면") {
                then("변경 없이 retried=false 로 멱등하다") {
                    val food = saveFood("대기찌개")
                    val outbox = saveOutbox(food.id)

                    postRetry(outbox.id).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.retried") { value(false) }
                        jsonPath("$.payload.outboxStatus") { value("PENDING") }
                    }
                }
            }

            `when`("COMPLETE 건을 재시도하면") {
                then("변경 없이 retried=false 와 현재 상태를 내려준다") {
                    val food = saveFood("완료찌개")
                    val outbox = saveOutbox(food.id, FoodVectorOutboxStatus.COMPLETE)

                    postRetry(outbox.id).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.retried") { value(false) }
                        jsonPath("$.payload.outboxStatus") { value("COMPLETE") }
                    }

                    vectorOutboxRepository.findById(outbox.id).orElseThrow().outboxStatus shouldBe
                        FoodVectorOutboxStatus.COMPLETE
                }
            }

            `when`("없는 id 를 재시도하면") {
                then("400(FOOD-007) 로 거절한다") {
                    postRetry(999999).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-007") }
                    }
                }
            }
        }
    }
}
