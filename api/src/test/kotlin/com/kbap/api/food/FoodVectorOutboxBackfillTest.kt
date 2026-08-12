package com.kbap.api.food

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import javax.sql.DataSource

@SpringBootTest
@Import(MySqlContainerConfig::class)
class FoodVectorOutboxBackfillTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var vectorOutboxRepository: FoodVectorOutboxJpaRepository

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val backfillResources = PathMatchingResourcePatternResolver()
            .getResources("classpath*:db/migration/*__food_vector_outbox_backfill.sql")

        fun clear() {
            dataSource.connection.use { connection ->
                connection.createStatement().use {
                    it.execute("DELETE FROM food_content_outbox")
                    it.execute("DELETE FROM food_vector_outbox")
                    it.execute("DELETE FROM food")
                }
            }
        }

        fun saveFood(koreanName: String, contentStatus: FoodContentStatus): Food = foodRepository.save(
            Food(
                koreanName = koreanName,
                displayName = koreanName,
                imageRef = "images/food/$koreanName.webp",
                description = "구수한 $koreanName",
                longDescription = "$koreanName 는 한국의 대표적인 국물 요리다",
                spiciness = 3,
                ingredients = listOf(FoodIngredient("SOYBEAN", 100)),
                contentStatus = contentStatus,
            ),
        )

        fun softDelete(food: Food) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("UPDATE food SET status = 'DELETED' WHERE id = ?").use {
                    it.setLong(1, food.id)
                    it.executeUpdate()
                }
            }
        }

        fun runBackfill() {
            val sql = backfillResources.single().inputStream.reader().readText()
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach(statement::execute)
                }
            }
        }

        given("벡터 아웃박스 백필 마이그레이션") {
            `when`("클래스패스에서 백필 SQL 을 찾으면") {
                then("정확히 한 개 존재한다") {
                    backfillResources.size shouldBe 1
                }
            }

            `when`("조회 가능·활성 음식과 그 밖의 음식이 섞인 상태에서 실행하면") {
                then("조회 가능·활성 음식만 적재 대기 건으로 쌓는다") {
                    clear()
                    val ready = saveFood("김치찌개", FoodContentStatus.READY)
                    val anotherReady = saveFood("된장찌개", FoodContentStatus.READY)
                    saveFood("순두부찌개", FoodContentStatus.PENDING_REVIEW)
                    softDelete(saveFood("부대찌개", FoodContentStatus.READY))

                    runBackfill()

                    val outboxes = vectorOutboxRepository.findAll()
                    outboxes.map { it.foodId } shouldContainExactlyInAnyOrder listOf(ready.id, anotherReady.id)
                    outboxes.map { it.operation }.toSet() shouldBe setOf(FoodVectorOutboxOperation.UPSERT)
                    outboxes.map { it.outboxStatus }.toSet() shouldBe setOf(FoodVectorOutboxStatus.PENDING)
                }
            }
        }
    }
}
