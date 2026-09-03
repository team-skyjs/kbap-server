package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.metering.LlmCallCostJpaRepository
import com.kbap.common.domain.metering.model.LlmCallCost
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import java.math.BigDecimal
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
class AdminLlmCostReportTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var llmCallCostRepository: LlmCallCostJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val path = "/api/admin/dashboard/llm-costs"

        fun tokenOf(role: MemberRole): String = tokenIssuer.issueAccessToken(0, role)

        fun getReport(query: String = "", token: String? = tokenOf(MemberRole.ADMIN)): ResultActionsDsl =
            mockMvc.get("$path$query") { token?.let { header("Authorization", "Bearer $it") } }

        fun saveCost(model: String, costUsd: String, inputTokens: Long = 100, outputTokens: Long = 50) {
            llmCallCostRepository.save(
                LlmCallCost(
                    modelName = model,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    costUsd = BigDecimal(costUsd),
                    costKrw = BigDecimal("1.00"),
                ),
            )
        }

        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("LLM 비용 레포트 API") {
            `when`("오늘 두 모델의 호출 비용이 있으면") {
                then("일자별로 모델 합계를 비용 내림차순으로 내려준다") {
                    saveCost("gpt-5.6-luna", "0.010000")
                    saveCost("gpt-5.6-luna", "0.020000")
                    saveCost("text-embedding-3-small", "0.000100")

                    getReport("?days=7").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.days.length()") { value(7) }
                        jsonPath("$.payload.days[6].callCount") { value(3) }
                        jsonPath("$.payload.days[6].models.length()") { value(2) }
                        jsonPath("$.payload.days[6].models[0].modelName") { value("gpt-5.6-luna") }
                        jsonPath("$.payload.days[6].models[0].callCount") { value(2) }
                        jsonPath("$.payload.days[6].models[0].inputTokens") { value(200) }
                        jsonPath("$.payload.days[6].date") { exists() }
                        jsonPath("$.payload.days[0].callCount") { value(0) }
                    }
                }
            }

            `when`("days 를 생략하면") {
                then("기본 7일 시리즈를 내려준다") {
                    getReport().andExpect {
                        status { isOk() }
                        jsonPath("$.payload.days.length()") { value(7) }
                    }
                }
            }

            `when`("days 가 허용 범위(1..30)를 벗어나면") {
                then("400(COMMON-002) 로 거절한다") {
                    getReport("?days=0").andExpect { status { isBadRequest() } }
                    getReport("?days=31").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("USER 역할 토큰으로 호출하면") {
                then("403(AUTH-008) 으로 거절한다") {
                    getReport(token = tokenOf(MemberRole.USER)).andExpect {
                        status { isForbidden() }
                        jsonPath("$.code") { value("AUTH-008") }
                    }
                }
            }
        }
    }
}
