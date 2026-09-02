package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import java.time.LocalDateTime
import javax.sql.DataSource

@IntegrationTest
class AdminContentOutboxControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var outboxRepository: FoodContentOutboxJpaRepository

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val path = "/api/admin/foods/content-outboxes"

        fun tokenOf(role: MemberRole): String = tokenIssuer.issueAccessToken(0, role)

        fun getPage(query: String = "", token: String? = tokenOf(MemberRole.ADMIN)): ResultActionsDsl =
            mockMvc.get("$path$query") { token?.let { header("Authorization", "Bearer $it") } }

        fun saveOutbox(displayName: String): FoodContentOutbox {
            val food = foodJpaRepository.save(Food(koreanName = displayName, description = "설명"))
            return outboxRepository.save(FoodContentOutbox.pending(food.id, displayName))
        }

        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("콘텐츠 아웃박스 목록 조회 API") {
            `when`("수집 요청이 여러 건 있으면") {
                then("id 내림차순 목록과 전체 건수를 내려준다") {
                    saveOutbox("된장찌개")
                    val last = saveOutbox("김치찌개")

                    getPage().andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(2) }
                        jsonPath("$.payload.items[0].id") { value(last.id) }
                        jsonPath("$.payload.items[0].foodId") { value(last.foodId) }
                        jsonPath("$.payload.items[0].displayName") { value("김치찌개") }
                        jsonPath("$.payload.items[0].outboxStatus") { value("PENDING") }
                        jsonPath("$.payload.items[0].attempts") { value(0) }
                        jsonPath("$.payload.items[0].createdAt") { exists() }
                        jsonPath("$.payload.totalCount") { value(2) }
                        jsonPath("$.payload.page") { value(1) }
                    }
                }
            }

            `when`("status 필터를 주면") {
                then("해당 상태 건만 내려준다") {
                    saveOutbox("된장찌개")
                    val sent = saveOutbox("김치찌개")
                    outboxRepository.save(
                        sent.apply {
                            outboxStatus = FoodContentOutboxStatus.SENT
                            sentAt = LocalDateTime.now()
                        },
                    )

                    getPage("?status=SENT").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].id") { value(sent.id) }
                        jsonPath("$.payload.items[0].sentAt") { exists() }
                        jsonPath("$.payload.totalCount") { value(1) }
                    }
                }
            }

            `when`("검색어 q 를 주면") {
                then("요청 시점 표시 이름 부분 일치 건만 내려준다") {
                    saveOutbox("김치찌개")
                    saveOutbox("된장찌개")

                    getPage("?q=김치").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].displayName") { value("김치찌개") }
                    }
                }
            }

            `when`("숫자 q 를 주면") {
                then("foodId 일치도 매칭한다") {
                    saveOutbox("김치찌개")
                    val matched = saveOutbox("된장찌개")

                    getPage("?q=${matched.foodId}").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].id") { value(matched.id) }
                    }
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
    }
}
