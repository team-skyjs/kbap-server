package com.kbap.api.migration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.common.core.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.flywaydb.core.Flyway
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

// 앱 컨텍스트의 공유 컨테이너와 분리된 전용 컨테이너 — Flyway 를 schema 단계까지만 적용해
// profile JSON 시드 후 backfill 단계를 재생한다(drop 마이그레이션이 추가돼도 target 덕에 불변).
class MemberProfileBackfillTest : BehaviorSpec({

    val container = MySQLContainer(DockerImageName.parse(MySqlContainerConfig.MYSQL_IMAGE))
        .withDatabaseName(MySqlContainerConfig.DATABASE_NAME)
        .withUsername(MySqlContainerConfig.USERNAME)
        .withPassword(MySqlContainerConfig.PASSWORD)
        .withCommand(
            "--character-set-server=utf8mb4",
            "--collation-server=utf8mb4_unicode_ci",
            "--default-time-zone=+09:00",
        )

    beforeSpec { container.start() }
    afterSpec { container.stop() }

    fun migrationVersionOf(descriptionKeyword: String): String {
        val dir = checkNotNull(javaClass.classLoader.getResource("db/migration")) {
            "db/migration 리소스 디렉터리를 찾을 수 없습니다"
        }
        val fileName = File(dir.toURI()).listFiles()
            ?.map { it.name }
            ?.firstOrNull { it.contains(descriptionKeyword) }
            ?: error("db/migration 에 '$descriptionKeyword' 마이그레이션이 없습니다")
        return fileName.removePrefix("V").substringBefore("__")
    }

    fun migrateTo(version: String) {
        Flyway.configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .target(version)
            .outOfOrder(true)
            .load()
            .migrate()
    }

    fun withConnection(block: (Connection) -> Unit) {
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use(block)
    }

    fun Connection.seedMember(providerUid: String, profileJson: String, status: String = "ACTIVE") {
        prepareStatement(
            """
            INSERT INTO member (provider, provider_uid, profile, member_status, onboarding_completed,
                                status, created_at, updated_at)
            VALUES ('GOOGLE', ?, ?, 'ACTIVE', 1, ?, NOW(6), NOW(6))
            """.trimIndent(),
        ).use {
            it.setString(1, providerUid)
            it.setString(2, profileJson)
            it.setString(3, status)
            it.executeUpdate()
        }
    }

    fun Connection.selectColumns(providerUid: String): Map<String, String?> =
        prepareStatement(
            """
            SELECT spiciness_preference, country_code, profile_image_url, avoidance_substance_codes
            FROM member WHERE provider_uid = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, providerUid)
            statement.executeQuery().use { rs ->
                check(rs.next()) { "provider_uid=$providerUid 행이 없습니다" }
                mapOf(
                    "spiciness" to rs.getString("spiciness_preference"),
                    "country" to rs.getString("country_code"),
                    "image" to rs.getString("profile_image_url"),
                    "codes" to rs.getString("avoidance_substance_codes"),
                )
            }
        }

    val objectMapper = jacksonObjectMapper()
    fun codesOf(columns: Map<String, String?>): List<String> = objectMapper.readValue(checkNotNull(columns["codes"]))

    given("profile JSON 이 채워진 회원들이 schema 단계까지 적용된 DB 에 있을 때") {
        `when`("backfill 마이그레이션을 적용하면") {
            then("모든 프로필 항목이 신규 컬럼으로 무손실 이관된다") {
                migrateTo(migrationVersionOf("member_profile_flatten_schema"))
                withConnection { conn ->
                    conn.seedMember(
                        providerUid = "uid-full",
                        profileJson = """{"avoidanceSubstanceCodes":["PEANUT","SHRIMP"],"spicinessPreference":"HOT","countryCode":"KR","profileImageUrl":"images/profile/full.png"}""",
                    )
                    conn.seedMember(
                        providerUid = "uid-legacy-slash",
                        profileJson = """{"avoidanceSubstanceCodes":[],"spicinessPreference":"MILD","countryCode":"US","profileImageUrl":"/images/profile/legacy.png"}""",
                    )
                    conn.seedMember(
                        providerUid = "uid-null-country",
                        profileJson = """{"avoidanceSubstanceCodes":["MILK"],"spicinessPreference":"SKIP","countryCode":null,"profileImageUrl":null}""",
                    )
                    conn.seedMember(
                        providerUid = "uid-missing-codes",
                        profileJson = """{"spicinessPreference":"NONE","countryCode":"JP","profileImageUrl":"images/profile/missing.png"}""",
                    )
                    conn.seedMember(
                        providerUid = "uid-deleted",
                        profileJson = """{"avoidanceSubstanceCodes":["EGG"],"spicinessPreference":"EXTREME","countryCode":"VN","profileImageUrl":"images/profile/deleted.png"}""",
                        status = "DELETED",
                    )
                }

                migrateTo(migrationVersionOf("member_profile_flatten_backfill"))

                withConnection { conn ->
                    val full = conn.selectColumns("uid-full")
                    full["spiciness"] shouldBe "HOT"
                    full["country"] shouldBe "KR"
                    full["image"] shouldBe "images/profile/full.png"
                    codesOf(full) shouldBe listOf("PEANUT", "SHRIMP")

                    val legacySlash = conn.selectColumns("uid-legacy-slash")
                    legacySlash["spiciness"] shouldBe "MILD"
                    legacySlash["image"] shouldBe "images/profile/legacy.png"
                    codesOf(legacySlash) shouldBe emptyList()

                    val nullCountry = conn.selectColumns("uid-null-country")
                    nullCountry["spiciness"] shouldBe "SKIP"
                    nullCountry["country"].shouldBeNull()
                    nullCountry["image"].shouldBeNull()
                    codesOf(nullCountry) shouldBe listOf("MILK")

                    val missingCodes = conn.selectColumns("uid-missing-codes")
                    missingCodes["country"] shouldBe "JP"
                    codesOf(missingCodes) shouldBe emptyList()

                    val deleted = conn.selectColumns("uid-deleted")
                    deleted["spiciness"] shouldBe "EXTREME"
                    deleted["country"] shouldBe "VN"
                    codesOf(deleted) shouldBe listOf("EGG")
                }
            }
        }

        `when`("백필 완료 상태에서 프로필 항목 컬럼으로 필터 조회하면") {
            then("JSON 파싱 없이 항목 값으로 직접 조회된다") {
                withConnection { conn ->
                    conn.prepareStatement("SELECT provider_uid FROM member WHERE country_code = ?").use { statement ->
                        statement.setString(1, "KR")
                        statement.executeQuery().use { rs ->
                            val uids = buildList { while (rs.next()) add(rs.getString("provider_uid")) }
                            uids shouldBe listOf("uid-full")
                        }
                    }
                    conn.prepareStatement(
                        "SELECT COUNT(*) FROM member WHERE spiciness_preference = ?",
                    ).use { statement ->
                        statement.setString(1, "HOT")
                        statement.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1) shouldBe 1
                        }
                    }
                }
            }
        }
    }
})
