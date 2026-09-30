package com.kbap.api.food

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import com.kbap.common.domain.food.model.ImageBatchItem
import com.kbap.common.domain.food.model.ImageBatchItemStatus
import com.kbap.common.domain.food.model.RegenerationIntent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@IntegrationTest
class PublishedFoodRestorerSerializationTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var restorer: PublishedFoodRestorer
    @Autowired private lateinit var foodRepository: FoodJpaRepository
    @Autowired private lateinit var vectorOutboxRepository: FoodVectorOutboxJpaRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var dataSource: DataSource

    init {
        beforeEach { TestTables.clearAll(dataSource) }

        fun failedReplacement(foodId: Long) = ImageBatchItem(
            batchId = 1L,
            foodId = foodId,
            itemStatus = ImageBatchItemStatus.FAILED,
            regenerationIntent = RegenerationIntent.REPLACE_BETTER,
        )

        given("교체 재생성 실패로 같은 음식의 공개를 되돌리는 두 트랜잭션") {
            `when`("앞 트랜잭션이 음식을 읽고 아웃박스를 넣은 채 커밋을 미루는 동안 뒤 트랜잭션이 같은 음식을 되돌리면") {
                then("교착 없이 앞 뒤로 직렬화돼 둘 다 끝나고 음식은 READY, UPSERT 아웃박스는 대기 1건이다 — 음식 행을 먼저 잠근다") {
                    val food = foodRepository.save(
                        Food(koreanName = "복원직렬화음식", description = "설명", imageRef = "images/webp/restore.webp", contentStatus = FoodContentStatus.PENDING_IMAGE),
                    )
                    val firstHolds = CountDownLatch(1)
                    val executor = Executors.newSingleThreadExecutor()
                    val first = executor.submit {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            restorer.restoreFailed(listOf(failedReplacement(food.id)))
                            firstHolds.countDown()
                            Thread.sleep(1_500)
                        }
                    }
                    executor.shutdown()
                    firstHolds.await(30, TimeUnit.SECONDS) shouldBe true

                    val startedAt = System.nanoTime()
                    val secondFailure = runCatching {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            restorer.restoreFailed(listOf(failedReplacement(food.id)))
                        }
                    }.exceptionOrNull()
                    val secondMillis = (System.nanoTime() - startedAt) / 1_000_000
                    val firstFailure = runCatching { first.get(30, TimeUnit.SECONDS) }.exceptionOrNull()

                    listOfNotNull(firstFailure?.cause ?: firstFailure, secondFailure).map { it.javaClass.simpleName } shouldBe emptyList()
                    (secondMillis in 500..10_000) shouldBe true
                    foodRepository.findById(food.id).get().contentStatus shouldBe FoodContentStatus.READY
                    vectorOutboxRepository.findByFoodIdAndOperationAndOutboxStatus(food.id, FoodVectorOutboxOperation.UPSERT, FoodVectorOutboxStatus.PENDING).size shouldBe 1
                }
            }
        }
    }
}
