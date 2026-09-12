package com.kbap.api.food

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.admin.AdminFoodService
import com.kbap.common.domain.food.FoodImageJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodImage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

@IntegrationTest
class FoodImageGalleryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var foodImageRepository: FoodImageJpaRepository

    @Autowired
    private lateinit var adminFoodService: AdminFoodService

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    init {
        val backfillSql = ClassPathResource(
            "db/migration/V2026.09.03.18.12.30__backfill_food_image_primary.sql",
        ).inputStream.bufferedReader().readText()
            .lines()
            .filterNot { it.trim().startsWith("--") }
            .joinToString("\n")
            .trim()
            .removeSuffix(";")

        fun inTx(block: () -> Unit): Unit =
            TransactionTemplate(transactionManager).executeWithoutResult { block() }

        fun runBackfill(): Unit =
            dataSource.connection.use { c -> c.createStatement().use { it.execute(backfillSql) } }

        fun saveFood(koreanName: String, imageRef: String? = null): Food =
            foodRepository.save(Food(koreanName = koreanName, description = "설명", imageRef = imageRef))

        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("food_image 백필 마이그레이션") {
            `when`("image_ref 유무가 섞인 음식들에 백필을 실행하면") {
                then("image_ref 있는 음식마다 primary 행 1개, 없으면 0개다") {
                    val withImage = saveFood("이미지찌개", imageRef = "images/webp/food/aa_bb.webp")
                    val withoutImage = saveFood("무이미지찌개")

                    runBackfill()

                    val primary = foodImageRepository.findByFoodIdAndIsPrimaryTrue(withImage.id)!!
                    primary.imageKey shouldBe "images/webp/food/aa_bb.webp"
                    foodImageRepository.findByFoodIdOrderBySortOrderAscIdAsc(withImage.id).size shouldBe 1
                    foodImageRepository.findByFoodIdOrderBySortOrderAscIdAsc(withoutImage.id).size shouldBe 0
                }
            }

            `when`("이미 갤러리 행이 있는 음식에 다시 실행하면") {
                then("건너뛰어 멱등하다") {
                    val food = saveFood("멱등이미지찌개", imageRef = "images/webp/food/cc_dd.webp")
                    runBackfill()

                    runBackfill()

                    foodImageRepository.findByFoodIdOrderBySortOrderAscIdAsc(food.id).size shouldBe 1
                }
            }
        }

        given("primary 유니크 제약") {
            `when`("같은 음식에 primary 행을 두 번 저장하면") {
                then("생성 컬럼 유니크가 거절한다 — 음식당 대표 1장은 DB 가 보장") {
                    val food = saveFood("유니크찌개")
                    foodImageRepository.save(FoodImage.primary(food.id, "images/webp/food/1_a.webp"))

                    shouldThrow<DataIntegrityViolationException> {
                        foodImageRepository.save(FoodImage.primary(food.id, "images/webp/food/2_b.webp"))
                        foodImageRepository.flush()
                    }
                }
            }

            `when`("promoteAsPrimary 로 대표를 교체하면") {
                then("기존 primary 는 내려가고 새 행만 primary 다 — image_ref 불변식의 갤러리 쪽 절반") {
                    val food = saveFood("교체찌개")
                    inTx { foodImageRepository.promoteAsPrimary(food.id, "images/webp/food/old.webp") }

                    inTx { foodImageRepository.promoteAsPrimary(food.id, "images/webp/food/new.webp") }

                    val gallery = foodImageRepository.findByFoodIdOrderBySortOrderAscIdAsc(food.id)
                    gallery.size shouldBe 2
                    gallery.single { it.isPrimary }.imageKey shouldBe "images/webp/food/new.webp"
                }
            }
        }

        given("음식 삭제·복원과 갤러리 보존") {
            `when`("음식을 소프트삭제 후 복원하면") {
                then("갤러리 행과 primary·image_ref 가 그대로 보존된다") {
                    val food = saveFood("보존찌개", imageRef = "images/webp/food/keep.webp")
                    runBackfill()

                    adminFoodService.deleteFood(food.id)
                    foodImageRepository.findByFoodIdOrderBySortOrderAscIdAsc(food.id).size shouldBe 1

                    adminFoodService.restoreFood(food.id)

                    val restored = foodRepository.findById(food.id).orElseThrow()
                    restored.imageRef shouldBe "images/webp/food/keep.webp"
                    foodImageRepository.findByFoodIdAndIsPrimaryTrue(food.id)!!.imageKey shouldBe restored.imageRef
                }
            }
        }
    }
}
