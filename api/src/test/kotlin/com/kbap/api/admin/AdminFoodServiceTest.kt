package com.kbap.api.admin

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import javax.sql.DataSource

@SpringBootTest
@Import(MySqlContainerConfig::class)
class AdminFoodServiceTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var service: AdminFoodService

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun clearFoods() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DELETE FROM food") }
            }
        }

        fun saveFood(koreanName: String): Long =
            foodJpaRepository.save(Food(koreanName = koreanName, description = "구수한 $koreanName")).id

        fun foodsByNames(names: Set<String>): Map<String, Food> =
            foodJpaRepository.findByKoreanNameIn(names).associateBy { it.koreanName }

        beforeContainer { clearFoods() }

        given("관리자 음식 시드(seedIncomplete)") {
            `when`("전부 새 이름이면") {
                then("모두 INCOMPLETE 로 생성되고 created 로 센다") {
                    val result = service.seedIncomplete(setOf("시드마라샹궈", "시드탕후루", "시드쌀국수"))

                    result shouldBe SeedIncompleteResult(requested = 3, created = 3, skipped = 0)
                    foodJpaRepository.count() shouldBe 3
                    foodsByNames(setOf("시드마라샹궈", "시드탕후루", "시드쌀국수"))
                        .values.forEach { it.isReady() shouldBe false }
                }
            }

            `when`("정규화되지 않은 표기가 섞여 있으면") {
                then("정규화(NFC·한글만)된 이름으로 저장·중복 판정한다") {
                    val result = service.seedIncomplete(setOf("김치 찌개", "김치찌개", "Kimchi 김치찌개!", "abc123"))

                    result shouldBe SeedIncompleteResult(requested = 1, created = 1, skipped = 0)
                    foodsByNames(setOf("김치찌개")).keys shouldBe setOf("김치찌개")
                    foodJpaRepository.count() shouldBe 1
                }
            }

            `when`("기존 이름과 새 이름이 섞여 있으면") {
                then("새 이름만 생성하고 기존은 skipped 로 센다") {
                    val existingId = saveFood("시드비빔밥")

                    val result = service.seedIncomplete(setOf("시드비빔밥", "시드김치찌개", "시드잡채"))

                    result shouldBe SeedIncompleteResult(requested = 3, created = 2, skipped = 1)
                    foodJpaRepository.count() shouldBe 3
                    foodsByNames(setOf("시드비빔밥")).getValue("시드비빔밥").id shouldBe existingId
                }
            }

            `when`("전부 기존 이름이면") {
                then("생성 없이 skipped 로만 세고 성공한다") {
                    saveFood("시드국밥")
                    saveFood("시드냉면")

                    val result = service.seedIncomplete(setOf("시드국밥", "시드냉면"))

                    result shouldBe SeedIncompleteResult(requested = 2, created = 0, skipped = 2)
                    foodJpaRepository.count() shouldBe 2
                }
            }

            `when`("빈 집합이거나 정규화 후 남는 이름이 없으면") {
                then("쿼리 없이 (0,0,0) 을 돌려준다") {
                    service.seedIncomplete(emptySet()) shouldBe SeedIncompleteResult(requested = 0, created = 0, skipped = 0)
                    service.seedIncomplete(setOf("abc", "123", "  ")) shouldBe SeedIncompleteResult(requested = 0, created = 0, skipped = 0)
                }
            }

            `when`("같은 목록으로 두 번 적재하면") {
                then("두 번째는 created=0 으로 성공하고 행 수가 늘지 않는다") {
                    val names = setOf("멱등마라탕", "멱등탕수육")

                    service.seedIncomplete(names) shouldBe SeedIncompleteResult(requested = 2, created = 2, skipped = 0)
                    service.seedIncomplete(names) shouldBe SeedIncompleteResult(requested = 2, created = 0, skipped = 2)
                    foodJpaRepository.count() shouldBe 2
                }
            }

            `when`("소프트 삭제된 동명 음식만 있으면") {
                then("되살리지도 새로 만들지도 않고 skipped 로 집계한다") {
                    val ghostId = saveFood("유령시드라면")
                    val ghost = foodJpaRepository.findById(ghostId).get()
                    ghost.delete()
                    foodJpaRepository.save(ghost)

                    val result = service.seedIncomplete(setOf("유령시드라면", "생존시드라면"))

                    result shouldBe SeedIncompleteResult(requested = 2, created = 1, skipped = 1)
                    foodsByNames(setOf("생존시드라면")).keys shouldBe setOf("생존시드라면")
                }
            }

            `when`("동일 목록을 두 스레드가 동시에 적재하면") {
                then("각 이름은 정확히 한 행만 저장되고 created 합계도 실제 생성 수와 일치한다") {
                    val names = setOf("경합마라탕", "경합쌀국수", "경합분짜")
                    val barrier = CyclicBarrier(2)

                    val results = (1..2).map {
                        CompletableFuture.supplyAsync {
                            barrier.await()
                            service.seedIncomplete(names)
                        }
                    }.map { it.join() }

                    results.forEach { it.requested shouldBe 3 }
                    results.sumOf { it.created } shouldBe 3
                    foodJpaRepository.count() shouldBe 3
                    foodsByNames(names).keys shouldBe names
                }
            }
        }
    }
}
