package com.kbap.api.admin

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.SocialProvider
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
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
    }
}
