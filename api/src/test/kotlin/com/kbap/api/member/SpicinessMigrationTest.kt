package com.kbap.api.member

import com.kbap.common.core.testsupport.MySqlContainerConfig
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ScriptUtils

@SpringBootTest
@Import(MySqlContainerConfig::class)
class SpicinessMigrationTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var dataSource: DataSource

    // 이관 마이그레이션 파일명(버전) 변경 시 이 경로도 함께 갱신할 것
    private val migrationResource = "db/migration/V2026.07.30.15.02.02__member_spiciness_enum.sql"

    init {
        fun insertLegacyMember(uid: String, profileJson: String) {
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "INSERT INTO member (provider, provider_uid, profile, status, created_at, updated_at) " +
                        "VALUES ('GOOGLE', ?, ?, 'ACTIVE', NOW(6), NOW(6))",
                ).use { ps ->
                    ps.setString(1, uid)
                    ps.setString(2, profileJson)
                    ps.executeUpdate()
                }
            }
        }

        fun deleteMember(uid: String) {
            dataSource.connection.use { c ->
                c.prepareStatement("DELETE FROM member WHERE provider_uid = ?").use { ps ->
                    ps.setString(1, uid)
                    ps.executeUpdate()
                }
            }
        }

        fun spicinessOf(uid: String): String? =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT profile->>'$.spicinessPreference' FROM member WHERE provider_uid = ?",
                ).use { ps ->
                    ps.setString(1, uid)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }

        fun runMigration() {
            dataSource.connection.use { c ->
                ScriptUtils.executeSqlScript(c, ClassPathResource(migrationResource))
            }
        }

        given("정수 맵기 프로필을 가진 기존 회원 이관") {
            val expected = mapOf(
                -1 to "SKIP",
                0 to "NONE",
                1 to "MILD",
                3 to "MILD",
                4 to "MEDIUM",
                6 to "MEDIUM",
                7 to "HOT",
                8 to "HOT",
                9 to "EXTREME",
                10 to "EXTREME",
            )

            `when`("대표 정수 값별 회원에 이관을 적용하면") {
                then("매핑 규칙대로 단계 문자열이 저장된다") {
                    expected.keys.forEach { value ->
                        insertLegacyMember("legacy-$value", """{"avoidanceSubstanceCodes":[],"spicinessPreference":$value}""")
                    }

                    runMigration()

                    expected.forEach { (value, stage) ->
                        spicinessOf("legacy-$value") shouldBe stage
                        deleteMember("legacy-$value")
                    }
                }
            }

            `when`("맵기 속성이 결손된 회원에 이관을 적용하면") {
                then("미설정(SKIP)으로 기입된다") {
                    insertLegacyMember("legacy-missing", """{"avoidanceSubstanceCodes":[]}""")

                    runMigration()

                    spicinessOf("legacy-missing") shouldBe "SKIP"
                    deleteMember("legacy-missing")
                }
            }

            `when`("이미 단계 문자열로 이관된 회원에 다시 적용하면") {
                then("값이 그대로 유지된다(재실행 안전)") {
                    insertLegacyMember("legacy-done", """{"spicinessPreference":"HOT"}""")

                    runMigration()

                    spicinessOf("legacy-done") shouldBe "HOT"
                    deleteMember("legacy-done")
                }
            }

            `when`("매핑 범위 밖 정수가 저장된 회원이 있으면") {
                then("조용히 흡수하지 않고 이관이 실패한다") {
                    insertLegacyMember("legacy-broken", """{"spicinessPreference":99}""")

                    shouldThrowAny { runMigration() }

                    deleteMember("legacy-broken")
                }
            }
        }
    }
}
