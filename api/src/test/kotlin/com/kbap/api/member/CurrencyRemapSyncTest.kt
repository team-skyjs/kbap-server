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
    val backfillCurrencies = Regex("""THEN\s+'([A-Z]{3})'""").findAll(backfillSql).map { it.groupValues[1] }.toSet()
    val currentCurrencies = CurrencyCode.entries.map { it.name }.toSet()

    given("취급 통화 축소와 회원 데이터") {
        `when`("KB-322 백필 SQL 을 읽으면") {
            then("내용이 비어 있지 않다 — 파일 경로가 어긋나면 여기서 먼저 걸린다") {
                backfillSql.isNotBlank() shouldBe true
            }
        }

        `when`("백필 SQL 의 통화 집합에서 현재 취급 통화를 빼면") {
            then("폐기 18종과 정확히 일치한다 — 이 목록의 회원 통화는 수동 UPDATE 로 USD 이관한다(KB-349, Flyway 미사용 결정)") {
                (backfillCurrencies - currentCurrencies) shouldBe setOf(
                    "AED", "BDT", "BHD", "BND", "EGP", "FJD", "JOD", "KHR", "KWD",
                    "KZT", "MNT", "NPR", "PKR", "QAR", "RUB", "SAR", "TWD", "VND",
                )
            }
        }

        `when`("현재 취급 통화가 백필 SQL 에 없는 경우를 찾으면") {
            then("ISK·RON 뿐이다 — 신규 추가 통화") {
                (currentCurrencies - backfillCurrencies).sorted() shouldBe listOf("ISK", "RON")
            }
        }
    }
})
