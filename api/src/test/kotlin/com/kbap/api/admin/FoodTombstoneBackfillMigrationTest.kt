package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import javax.sql.DataSource

@IntegrationTest
class FoodTombstoneBackfillMigrationTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val backfillSql = ClassPathResource(
            "db/migration/V2026.09.02.12.40.10__backfill_deleted_food_tombstone_names.sql",
        ).inputStream.bufferedReader().readText()
            .lines()
            .filterNot { it.trim().startsWith("--") }
            .joinToString("\n")
            .trim()
            .removeSuffix(";")

        fun runBackfill(): Unit =
            dataSource.connection.use { c -> c.createStatement().use { it.execute(backfillSql) } }

        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("삭제 개명 도입 전 tombstone backfill 마이그레이션") {
            `when`("원명을 그대로 쥔 기존 삭제 행에 backfill 을 실행하면") {
                then("tombstone 규칙으로 개명돼 동명 신규 생성이 성공한다") {
                    val legacy = foodJpaRepository.save(Food(koreanName = "레거시찌개", description = "설명"))
                    dataSource.connection.use { c ->
                        c.createStatement().use {
                            it.execute("UPDATE food SET status = 'DELETED' WHERE id = ${legacy.id}")
                        }
                    }

                    runBackfill()

                    val backfilled = foodJpaRepository.findAnyById(legacy.id)!!
                    backfilled.deletedOriginalKoreanName shouldBe "레거시찌개"
                    backfilled.koreanName shouldContain "_deleted_${legacy.id}"
                    foodJpaRepository.save(Food(koreanName = "레거시찌개", description = "설명")).id
                }
            }

            `when`("이미 원명이 보존된 행에 다시 실행하면") {
                then("건너뛰어 멱등하다") {
                    val legacy = foodJpaRepository.save(Food(koreanName = "멱등찌개", description = "설명"))
                    dataSource.connection.use { c ->
                        c.createStatement().use {
                            it.execute("UPDATE food SET status = 'DELETED' WHERE id = ${legacy.id}")
                        }
                    }
                    runBackfill()
                    val once = foodJpaRepository.findAnyById(legacy.id)!!.koreanName

                    runBackfill()

                    foodJpaRepository.findAnyById(legacy.id)!!.koreanName shouldBe once
                    foodJpaRepository.findAnyById(legacy.id)!!.deletedOriginalKoreanName shouldBe "멱등찌개"
                }
            }
        }
    }
}
