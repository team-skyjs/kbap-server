package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.food.model.FoodContentStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import javax.sql.DataSource
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.ImageBatchJpaRepository
import com.kbap.common.domain.food.model.ImageBatch
import com.kbap.common.domain.food.model.ImageBatchItem
import com.kbap.common.domain.food.model.RegenerationIntent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeGreaterThan
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future

@IntegrationTest
class AdminFoodRecollectTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var adminFoodService: AdminFoodService

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var outboxRepository: FoodContentOutboxJpaRepository

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var imageBatchJpaRepository: ImageBatchJpaRepository

    @Autowired
    private lateinit var imageBatchItemJpaRepository: ImageBatchItemJpaRepository

    init {
        val namePrefix = "재수집-"

        fun clearFoods(): Unit =
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute("DELETE FROM food_content_outbox")
                    it.execute("DELETE FROM image_batch_item")
                    it.execute("DELETE FROM image_batch")
                    it.execute("DELETE FROM food_vector_outbox")
                    it.execute("DELETE FROM food_image")
                    it.execute("DELETE FROM food")
                }
            }

        fun saveFood(rawName: String, contentStatus: FoodContentStatus = FoodContentStatus.READY): Food =
            foodJpaRepository.save(
                Food(
                    koreanName = namePrefix + rawName,
                    displayName = namePrefix + rawName,
                    description = "구수한 $rawName",
                    spiciness = 1,
                    contentStatus = contentStatus,
                ),
            )

        fun pendingFoodIds(): List<Long> =
            outboxRepository.findByOutboxStatusOrderByIdAsc(FoodContentOutboxStatus.PENDING).map { it.foodId }

        fun holdFoodLockWhileStartingRegeneration(foodId: Long, locked: CountDownLatch): Future<*> =
            Executors.newSingleThreadExecutor().let { executor ->
                executor.submit {
                    TransactionTemplate(transactionManager).execute {
                        foodJpaRepository.findByIdForUpdate(foodId)
                        val batch = imageBatchJpaRepository.save(ImageBatch(promptVersion = "v1", model = "gpt-image-2"))
                        imageBatchItemJpaRepository.saveAndFlush(
                            ImageBatchItem(batchId = batch.id, foodId = foodId, regenerationIntent = RegenerationIntent.WRONG_IMAGE),
                        )
                        locked.countDown()
                        Thread.sleep(1_500)
                    }
                }.also { executor.shutdown() }
            }

        given("재생성이 음식 행을 잠그고 시작되는 동안 들어온 재수집") {
            `when`("일괄 재수집이 그 음식을 대상으로 잡고 잠금을 기다리면") {
                then("잠금 뒤에 커밋된 재생성을 보고 건너뛴다 — 잠금이 그 음식 트랜잭션의 첫 DB 연산이라 옛 스냅샷으로 판정하지 않는다") {
                    clearFoods()
                    val food = saveFood("잠금대기칼국수")
                    val locked = CountDownLatch(1)
                    val regeneration = holdFoodLockWhileStartingRegeneration(food.id, locked)
                    if (!locked.await(30, java.util.concurrent.TimeUnit.SECONDS)) regeneration.get(1, java.util.concurrent.TimeUnit.SECONDS)

                    val started = System.nanoTime()
                    val result = adminFoodService.requestRecollect(query = "잠금대기칼국수", status = null)
                    Duration.ofNanos(System.nanoTime() - started).toMillis() shouldBeGreaterThan 1_000L
                    regeneration.get(30, java.util.concurrent.TimeUnit.SECONDS)

                    result.requested shouldBe 1
                    result.created shouldBe 0
                    result.skippedRegenerating shouldBe 1
                    pendingFoodIds() shouldBe emptyList()
                }
            }

            `when`("단건 재수집이 잠금을 기다리면") {
                then("잠금 뒤에 커밋된 재생성을 보고 409(FOOD-020) 로 거절한다") {
                    clearFoods()
                    val food = saveFood("잠금대기콩국수")
                    val locked = CountDownLatch(1)
                    val regeneration = holdFoodLockWhileStartingRegeneration(food.id, locked)
                    if (!locked.await(30, java.util.concurrent.TimeUnit.SECONDS)) regeneration.get(1, java.util.concurrent.TimeUnit.SECONDS)

                    shouldThrow<BusinessException> { adminFoodService.requestRecollectForFood(food.id) }
                        .errorCode shouldBe ErrorCode.FOOD_CONTENT_AND_IMAGE_JOBS_CONFLICT
                    regeneration.get(30, java.util.concurrent.TimeUnit.SECONDS)

                    pendingFoodIds() shouldBe emptyList()
                }
            }
        }

        given("조건 일괄 재수집") {
            `when`("검색어에 걸린 음식이 있으면") {
                then("대상마다 대기 요청이 쌓인다") {
                    clearFoods()
                    val first = saveFood("칼국수")
                    val second = saveFood("콩국수")
                    saveFood("비빔밥")

                    val result = adminFoodService.requestRecollect(query = "국수", status = null)

                    result.requested shouldBe 2
                    result.created shouldBe 2
                    result.skipped shouldBe 0
                    pendingFoodIds() shouldBe listOf(first.id, second.id)
                }
            }

            `when`("상태 조건을 함께 주면") {
                then("그 상태의 음식만 대상이 된다") {
                    clearFoods()
                    val failed = saveFood("칼국수", FoodContentStatus.FAILED)
                    saveFood("콩국수", FoodContentStatus.READY)

                    val result = adminFoodService.requestRecollect(query = null, status = FoodContentStatus.FAILED)

                    result.created shouldBe 1
                    pendingFoodIds() shouldBe listOf(failed.id)
                }
            }

            `when`("이미 대기 중인 요청이 있는 음식이면") {
                then("중복 요청을 쌓지 않는다") {
                    clearFoods()
                    val food = saveFood("칼국수")
                    outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))

                    val result = adminFoodService.requestRecollect(query = "칼국수", status = null)

                    result.requested shouldBe 1
                    result.created shouldBe 0
                    result.skipped shouldBe 1
                    pendingFoodIds() shouldBe listOf(food.id)
                }
            }

            `when`("이전 요청이 이미 발행 완료됐으면") {
                then("새 요청을 만든다") {
                    clearFoods()
                    val food = saveFood("칼국수")
                    val sent = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
                    sent.markSent()
                    outboxRepository.save(sent)

                    val result = adminFoodService.requestRecollect(query = "칼국수", status = null)

                    result.created shouldBe 1
                    pendingFoodIds() shouldBe listOf(food.id)
                }
            }

            `when`("조건에 걸린 음식이 없으면") {
                then("아무 요청도 쌓지 않는다") {
                    clearFoods()
                    saveFood("비빔밥")

                    val result = adminFoodService.requestRecollect(query = "국수", status = null)

                    result.requested shouldBe 0
                    result.created shouldBe 0
                    pendingFoodIds() shouldBe emptyList()
                }
            }

            `when`("대상이 1회 상한을 넘으면") {
                then("실행을 거부하고 아무 요청도 만들지 않는다") {
                    clearFoods()
                    saveFood("칼국수")
                    saveFood("콩국수")

                    val result = adminFoodService.requestRecollect(query = "국수", status = null, max = 1)

                    result.exceeded shouldBe true
                    result.requested shouldBe 2
                    result.created shouldBe 0
                    pendingFoodIds() shouldBe emptyList()
                }
            }
        }
    }
}
