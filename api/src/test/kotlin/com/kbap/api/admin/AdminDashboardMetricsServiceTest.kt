package com.kbap.api.admin

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.domain.metering.LlmCallCostJpaRepository
import com.kbap.common.domain.metering.model.LlmCallCost
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import com.kbap.common.domain.scan.model.ScanHistory
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import javax.sql.DataSource

@SpringBootTest
@Import(MySqlContainerConfig::class)
class AdminDashboardMetricsServiceTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var service: AdminDashboardMetricsService

    @Autowired
    private lateinit var memberJpaRepository: MemberJpaRepository

    @Autowired
    private lateinit var scanHistoryJpaRepository: ScanHistoryJpaRepository

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var llmCallCostJpaRepository: LlmCallCostJpaRepository

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun clearAll() {
            dataSource.connection.use { c ->
                c.createStatement().use { st ->
                    listOf(
                        "food_review", "member_ranking_event", "bookmark", "uploaded_image",
                        "scan_history", "image_batch_item", "image_batch",
                        "food", "member_block", "member", "llm_call_cost",
                    ).forEach { st.execute("DELETE FROM $it") }
                }
            }
        }

        fun saveMember(uid: String, status: MemberStatus = MemberStatus.ACTIVE): Member =
            memberJpaRepository.save(
                Member(provider = SocialProvider.GOOGLE, providerUid = uid, memberStatus = status),
            )

        fun setCreatedAt(table: String, id: Long, daysAgo: Long) {
            dataSource.connection.use { c ->
                c.prepareStatement("UPDATE $table SET created_at = ? WHERE id = ?").use { ps ->
                    ps.setObject(1, LocalDateTime.now().minusDays(daysAgo).withHour(12).withMinute(0))
                    ps.setLong(2, id)
                    ps.executeUpdate()
                }
            }
        }

        fun saveScan(memberId: Long, daysAgo: Long) {
            val scan = scanHistoryJpaRepository.save(
                ScanHistory(memberId = memberId, imagePath = "scan/img.png", menuName = "menu", koreanName = "메뉴"),
            )
            setCreatedAt("scan_history", scan.id, daysAgo)
        }

        fun koreanDayLabel(date: LocalDate): String = when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> "월"
            DayOfWeek.TUESDAY -> "화"
            DayOfWeek.WEDNESDAY -> "수"
            DayOfWeek.THURSDAY -> "목"
            DayOfWeek.FRIDAY -> "금"
            DayOfWeek.SATURDAY -> "토"
            DayOfWeek.SUNDAY -> "일"
        }

        beforeContainer { clearAll() }

        given("대시보드 지표 - 총 가입자 수") {
            `when`("활성 회원과 정지 회원이 섞여 있으면") {
                then("활성 회원 수만 센다") {
                    saveMember("지표-활성1")
                    saveMember("지표-활성2")
                    saveMember("지표-활성3")
                    saveMember("지표-정지", MemberStatus.SUSPENDED)

                    service.getMetrics().totalActiveMembers shouldBe 3
                }
            }

            `when`("회원이 한 명도 없으면") {
                then("0 을 반환한다") {
                    service.getMetrics().totalActiveMembers shouldBe 0
                }
            }
        }

        given("대시보드 지표 - 최근 7일 스캔 횟수") {
            `when`("일부 날짜에만 스캔 이력이 있고 7일 밖 이력도 있으면") {
                then("과거→오늘 7원소로 해당일 카운트·누락일 0·경계 밖 제외로 집계한다") {
                    val member = saveMember("스캔-회원")
                    saveScan(member.id, daysAgo = 0)
                    saveScan(member.id, daysAgo = 0)
                    saveScan(member.id, daysAgo = 2)
                    saveScan(member.id, daysAgo = 6)
                    saveScan(member.id, daysAgo = 7)
                    saveScan(member.id, daysAgo = 10)

                    val today = LocalDate.now()
                    val scans = service.getMetrics().weeklyScans

                    scans.size shouldBe 7
                    scans.map { it.date } shouldBe (6L downTo 0L).map { today.minusDays(it) }
                    scans.last().count shouldBe 2
                    scans[4].count shouldBe 1
                    scans.first().count shouldBe 1
                    scans.sumOf { it.count } shouldBe 4
                    scans.map { it.dayLabel } shouldBe scans.map { koreanDayLabel(it.date) }
                }
            }

            `when`("스캔 이력이 전혀 없으면") {
                then("7원소 전부 0 으로 채운다") {
                    val scans = service.getMetrics().weeklyScans

                    scans.size shouldBe 7
                    scans.all { it.count == 0L } shouldBe true
                }
            }
        }

        given("대시보드 지표 - 최근 7일 신규 등록 음식") {
            fun saveFoodAt(koreanName: String, daysAgo: Long) {
                val food = foodJpaRepository.save(Food(koreanName = koreanName, description = "설명 $koreanName"))
                setCreatedAt("food", food.id, daysAgo)
            }

            `when`("7일 안·밖 등록 음식이 섞여 있으면") {
                then("7일 안 등록만 일자별로 집계하고 누락일은 0 이다") {
                    saveFoodAt("음식-오늘1", daysAgo = 0)
                    saveFoodAt("음식-오늘2", daysAgo = 0)
                    saveFoodAt("음식-사흘전", daysAgo = 3)
                    saveFoodAt("음식-일주일밖", daysAgo = 8)

                    val foods = service.getMetrics().weeklyNewFoods

                    foods.size shouldBe 7
                    foods.last().count shouldBe 2
                    foods[3].count shouldBe 1
                    foods.sumOf { it.count } shouldBe 3
                }
            }
        }

        given("대시보드 지표 - 최근 7일 LLM 호출 비용 리스트") {
            fun saveCost(model: String, costUsd: String, daysAgo: Long) {
                val cost = llmCallCostJpaRepository.save(
                    LlmCallCost(modelName = model, inputTokens = 10, outputTokens = 20, costUsd = BigDecimal(costUsd)),
                )
                setCreatedAt("llm_call_cost", cost.id, daysAgo)
            }

            `when`("여러 모델의 소수 비용 호출이 섞여 있으면") {
                then("최신순 7일 리스트로 날짜별 호출 횟수·비용 합계·모델별 상세를 내려준다") {
                    saveCost("gpt-test", "0.000123", daysAgo = 0)
                    saveCost("gpt-test", "0.000200", daysAgo = 0)
                    saveCost("gemini-test", "0.500000", daysAgo = 0)
                    saveCost("gpt-test", "1.500000", daysAgo = 5)
                    saveCost("gpt-test", "9.999999", daysAgo = 9)

                    val daily = service.getMetrics().llmCostDaily

                    daily.size shouldBe 7
                    daily.map { it.date } shouldBe (0L..6L).map { LocalDate.now().minusDays(it) }

                    val today = daily.first()
                    today.callCount shouldBe 3
                    today.costUsd.compareTo(BigDecimal("0.500323")) shouldBe 0
                    today.models.size shouldBe 2
                    val gpt = today.models.first { it.modelName == "gpt-test" }
                    gpt.callCount shouldBe 2
                    gpt.inputTokens shouldBe 20
                    gpt.outputTokens shouldBe 40
                    gpt.costUsd.compareTo(BigDecimal("0.000323")) shouldBe 0

                    daily[5].callCount shouldBe 1
                    daily[5].costUsd.compareTo(BigDecimal("1.5")) shouldBe 0
                    daily.count { it.callCount == 0L } shouldBe 5
                    daily.filter { it.callCount == 0L }.all { it.models.isEmpty() && it.costUsd.signum() == 0 } shouldBe true
                }
            }
        }
    }
}
