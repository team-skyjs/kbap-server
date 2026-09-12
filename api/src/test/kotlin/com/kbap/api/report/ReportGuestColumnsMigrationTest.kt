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

        fun uniqueExists(name: String): Boolean =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.statistics " +
                        "WHERE table_schema = DATABASE() AND table_name = 'report' AND index_name = ?",
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

            `when`("유니크 인덱스를 조회하면") {
                then("회원·게스트 유니크가 모두 존재한다") {
                    uniqueExists("uk_report_reporter_target") shouldBe true
                    uniqueExists("uk_report_reporter_installation_target") shouldBe true
                }
            }
        }
    }
}
