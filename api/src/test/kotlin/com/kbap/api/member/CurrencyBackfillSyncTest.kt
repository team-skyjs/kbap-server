package com.kbap.api.member

import com.kbap.common.domain.member.model.CountryCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class CurrencyBackfillSyncTest : BehaviorSpec({
    val backfillResourcePath = "db/migration/V2026.08.11.13.24.22__member_currency.sql"
    val sql = Thread.currentThread().contextClassLoader.getResource(backfillResourcePath)?.readText() ?: ""

    val branchRegex = Regex("""WHEN\s+'([A-Z]{2})'\s+THEN\s+'([A-Z]{3})'""")
    val branches = branchRegex.findAll(sql).associate { it.groupValues[1] to it.groupValues[2] }

    given("회원 통화 백필 마이그레이션") {
        `when`("SQL 을 읽으면") {
            then("내용이 비어 있지 않다 — 파일 경로가 어긋나면 여기서 먼저 걸린다") {
                sql.isNotBlank() shouldBe true
            }
        }

        `when`("CASE 분기의 국가 집합을 enum 과 비교하면") {
            then("197개 국가가 하나도 빠지지 않는다") {
                branches.keys.size shouldBe CountryCode.entries.size
                CountryCode.entries.map { it.name }.filterNot { it in branches }.shouldBeEmpty()
            }
        }

        `when`("각 분기의 통화를 enum 매핑과 비교하면") {
            then("전부 일치한다(드리프트 0)") {
                CountryCode.entries.forEach { country ->
                    branches[country.name] shouldBe country.currency.name
                }
            }
        }

        `when`("백필 조건을 확인하면") {
            then("국가가 없는 회원은 건드리지 않는다") {
                sql.contains("WHERE `country_code` IS NOT NULL") shouldBe true
            }
        }

        `when`("컬럼 정의를 확인하면") {
            then("nullable 로 추가한다 — 배포 중 구 리비전 공존을 위해") {
                val addColumnLine = sql.lines().first { it.contains("ADD COLUMN `currency`") }
                addColumnLine.contains("varchar(3)") shouldBe true
                addColumnLine.contains("NOT NULL") shouldBe false
            }
        }
    }
})
