package com.kbap.api.member

import com.kbap.common.domain.CurrencyCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class CurrencyRemapSyncTest : BehaviorSpec({
    fun readMigration(nameSuffix: String): String {
        val loader = Thread.currentThread().contextClassLoader
        val dir = loader.getResource("db/migration") ?: error("db/migration 리소스를 찾을 수 없다")
        val file = java.io.File(dir.toURI()).listFiles()
            ?.firstOrNull { it.name.endsWith(nameSuffix) }
            ?: error("$nameSuffix 마이그레이션을 찾을 수 없다")
        return file.readText()
    }

    val backfillSql = readMigration("__member_currency.sql")
    val remapSql = readMigration("__member_currency_remap.sql")

    val backfillCurrencies = Regex("""THEN\s+'([A-Z]{3})'""").findAll(backfillSql).map { it.groupValues[1] }.toSet()
    val remapCurrencies = Regex("""IN\s*\(([^)]*)\)""").find(remapSql)!!.groupValues[1]
        .split(",").map { it.trim().removeSurrounding("'") }.toSet()
    val currentCurrencies = CurrencyCode.entries.map { it.name }.toSet()

    given("회원 통화 리맵 마이그레이션") {
        `when`("두 SQL 을 읽으면") {
            then("내용이 비어 있지 않다 — 파일 경로가 어긋나면 여기서 먼저 걸린다") {
                backfillSql.isNotBlank() shouldBe true
                remapSql.isNotBlank() shouldBe true
            }
        }

        `when`("백필 SQL 의 통화 집합에서 현재 취급 통화를 빼면") {
            then("이번 리맵의 IN 목록과 정확히 일치한다") {
                (backfillCurrencies - currentCurrencies) shouldBe remapCurrencies
            }
        }

        `when`("리맵 대상과 현재 취급 통화를 교집합하면") {
            then("비어 있다 — 취급 중인 통화를 USD 로 밀어버리지 않는다") {
                (remapCurrencies intersect currentCurrencies).toList().shouldBeEmpty()
            }
        }

        `when`("리맵 조건을 확인하면") {
            then("USD 로만 이관한다") {
                remapSql.contains("SET `currency` = 'USD'") shouldBe true
            }
        }
    }
})
