package com.kbap.app.api.migration

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File

class MigrationLayoutTest : BehaviorSpec({
    val versionFormat = Regex("""V\d{4}\.\d{2}\.\d{2}\.\d{2}\.\d{2}\.\d{2}__.+\.sql""")
    val substanceRowRegex = Regex("""\(\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'[^']*'\s*\)""")

    val sqlFiles: (File) -> List<File> = { dir ->
        dir.listFiles { f -> f.isFile && f.extension == "sql" }?.sortedBy { it.name } ?: emptyList()
    }
    val migrationSqls = sqlFiles(File("src/main/resources/db/migration"))

    given("db/migration 디렉터리(prod 적용 위치)") {
        `when`("SQL 파일 목록을 세면") {
            then("정확히 2개다") {
                migrationSqls shouldHaveSize 2
            }
            then("하나는 __init_schema.sql, 하나는 __seed_avoidance_catalog.sql 로 끝난다") {
                val names = migrationSqls.map { it.name }
                names.any { it.endsWith("__init_schema.sql") } shouldBe true
                names.any { it.endsWith("__seed_avoidance_catalog.sql") } shouldBe true
            }
        }

        `when`("init_schema 를 읽으면") {
            then("INSERT 문이 없다(스키마 전용)") {
                val init = migrationSqls.firstOrNull { it.name.endsWith("__init_schema.sql") }
                init.shouldNotBeNull()
                init.readText().contains("INSERT", ignoreCase = true) shouldBe false
            }
        }

        `when`("seed_avoidance_catalog 의 avoidance_substance 행을 세면") {
            then("정확히 81건이다") {
                val seed = migrationSqls.firstOrNull { it.name.endsWith("__seed_avoidance_catalog.sql") }
                seed.shouldNotBeNull()
                substanceRowRegex.findAll(seed.readText()).count() shouldBe 81
            }
        }

        `when`("어떤 파일이든 데모 음식 INSERT 를 검사하면") {
            then("INSERT INTO food 가 존재하지 않는다(데모 유입 금지)") {
                val joined = migrationSqls.joinToString("\n") { it.readText() }
                joined.contains("INSERT INTO food", ignoreCase = true) shouldBe false
            }
        }
    }

    given("모든 마이그레이션 파일명") {
        `when`("KB-44 timestamp 버전 형식과 비교하면") {
            then("전부 V<yyyy.MM.dd.HH.mm.ss>__<slug>.sql 형식을 따른다") {
                migrationSqls.forEach { file ->
                    versionFormat.matches(file.name) shouldBe true
                }
            }
        }
    }
})
