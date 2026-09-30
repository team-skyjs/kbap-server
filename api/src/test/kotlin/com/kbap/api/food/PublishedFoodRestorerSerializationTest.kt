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
            `when`("둘 다 음식을 읽은 뒤에 함께 커밋하면") {
                then("교착 없이 둘 다 끝나고 음식은 READY, UPSERT 아웃박스는 대기 1건이다 — 음식 행을 먼저 잠가 직렬화한다") {
                    val food = foodRepository.save(
                        Food(koreanName = "복원직렬화음식", description = "설명", imageRef = "images/webp/restore.webp", contentStatus = FoodContentStatus.PENDING_IMAGE),
                    )
                    val bothRead = CountDownLatch(2)
                    val executor = Executors.newFixedThreadPool(2)
                    val runs = (1..2).map {
                        executor.submit {
                            TransactionTemplate(transactionManager).executeWithoutResult {
                                restorer.restoreFailed(listOf(failedReplacement(food.id)))
                                bothRead.countDown()
                                bothRead.await(10, TimeUnit.SECONDS)
                            }
                        }
                    }
                    executor.shutdown()

                    val failures = runs.mapNotNull { runCatching { it.get(60, TimeUnit.SECONDS) }.exceptionOrNull() }

                    failures.map { it.cause?.javaClass?.simpleName ?: it.javaClass.simpleName } shouldBe emptyList()
                    foodRepository.findById(food.id).get().contentStatus shouldBe FoodContentStatus.READY
                    vectorOutboxRepository.findByFoodIdAndOperationAndOutboxStatus(food.id, FoodVectorOutboxOperation.UPSERT, FoodVectorOutboxStatus.PENDING).size shouldBe 1
                }
            }
        }
    }
}
