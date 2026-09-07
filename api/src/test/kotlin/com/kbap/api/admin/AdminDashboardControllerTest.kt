package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import com.kbap.common.domain.scan.model.ScanHistory
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
class AdminDashboardControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var memberJpaRepository: MemberJpaRepository

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var scanHistoryJpaRepository: ScanHistoryJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val path = "/api/admin/dashboard/metrics"

        fun tokenOf(role: MemberRole): String = tokenIssuer.issueAccessToken(0, role)

        fun getMetrics(token: String? = tokenOf(MemberRole.ADMIN)): ResultActionsDsl =
            mockMvc.get(path) { token?.let { header("Authorization", "Bearer $it") } }

        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("어드민 대시보드 메트릭 API") {
            `when`("회원·스캔·검수 대기 데이터가 있으면") {
                then("핵심 지표와 주간 스캔 시리즈를 내려준다") {
                    val member = memberJpaRepository.save(
                        Member(provider = SocialProvider.GOOGLE, providerUid = "metrics-uid", email = "m@test.com"),
                    )
                    foodJpaRepository.save(
                        Food(koreanName = "검수대기찌개", description = "설명", contentStatus = FoodContentStatus.PENDING_REVIEW),
                    )
                    scanHistoryJpaRepository.save(ScanHistory.record(member.id, price = null, foodId = null))
                    scanHistoryJpaRepository.save(ScanHistory.record(member.id, price = null, foodId = null))

                    getMetrics().andExpect {
                        status { isOk() }
                        jsonPath("$.payload.totalActiveMembers") { value(1) }
                        jsonPath("$.payload.pendingReviewCount") { value(1) }
                        jsonPath("$.payload.weeklyScanCount") { value(2) }
                        jsonPath("$.payload.prevWeekScanCount") { value(0) }
                        jsonPath("$.payload.weeklyScans.length()") { value(7) }
                        jsonPath("$.payload.weeklyScans[6].count") { value(2) }
                        jsonPath("$.payload.weeklyScans[6].date") { exists() }
                    }
                }
            }

            `when`("USER 역할 토큰으로 호출하면") {
                then("403(AUTH-008) 으로 거절한다") {
                    getMetrics(token = tokenOf(MemberRole.USER)).andExpect {
                        status { isForbidden() }
                        jsonPath("$.code") { value("AUTH-008") }
                    }
                }
            }
        }
    }
}
