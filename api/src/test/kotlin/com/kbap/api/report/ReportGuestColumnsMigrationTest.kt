package com.kbap.api.report

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import javax.sql.DataSource

@IntegrationTest
class ReportGuestColumnsMigrationTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun column(name: String): Pair<String, String>? =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT is_nullable, data_type FROM information_schema.columns " +
                        "WHERE table_schema = DATABASE() AND table_name = 'report' AND column_name = ?",
                ).use { ps ->
                    ps.setString(1, name)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null }
                }
            }

        fun indexExists(name: String): Boolean =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.statistics " +
                        "WHERE table_schema = DATABASE() AND table_name = 'report' AND index_name = ?",
                ).use { ps ->
                    ps.setString(1, name)
                    ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) > 0 }
                }
            }

        fun uniqueIndexExists(name: String): Boolean =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.statistics " +
                        "WHERE table_schema = DATABASE() AND table_name = 'report' AND index_name = ? AND non_unique = 0",
                ).use { ps ->
                    ps.setString(1, name)
                    ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) > 0 }
                }
            }

        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("게스트 신고 마이그레이션 적용 후 report 스키마") {
            `when`("reporter 컬럼을 조회하면") {
                then("reporter_member_id 는 NULL 허용, reporter_installation_id(varchar) 가 추가돼 있다") {
                    column("reporter_member_id")?.first shouldBe "YES"
                    column("reporter_installation_id")?.first shouldBe "YES"
                    column("reporter_installation_id")?.second shouldBe "varchar"
                }
            }

            `when`("신고자 인덱스를 조회하면") {
                then("재신고 허용으로 회원 유니크는 사라지고 같은 컬럼 순서의 일반 인덱스만 남는다") {
                    indexExists("uk_report_reporter_target") shouldBe false
                    indexExists("idx_report_reporter_member") shouldBe true
                    indexExists("idx_report_reporter_installation") shouldBe true
                    uniqueIndexExists("idx_report_reporter_member") shouldBe false
                    uniqueIndexExists("idx_report_reporter_installation") shouldBe false
                }
            }
        }
    }
}
