package com.kbap.api.admin

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import com.kbap.common.domain.scan.model.ScanHistory
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
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
    private lateinit var dataSource: DataSource

    init {
        fun clearAll() {
            dataSource.connection.use { c ->
                c.createStatement().use { st ->
                    listOf(
                        "food_review", "member_ranking_event", "bookmark", "uploaded_image",
                        "scan_history", "image_batch_item", "image_batch",
                        "food", "member", "llm_call_cost",
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
    }
}
