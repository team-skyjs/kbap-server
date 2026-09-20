package com.kbap.batch.food.content

import com.kbap.batch.BatchIntegrationTest
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.LocalDateTime

@BatchIntegrationTest
class FoodContentOutboxRecoveryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var outboxRepository: FoodContentOutboxJpaRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    init {
        val staleAfter = Duration.ofHours(24)

        fun recovery(maxAttempts: Int = 5) =
            FoodContentOutboxRecovery(outboxRepository, transactionManager, staleAfter, maxAttempts)

        fun clear() {
            outboxRepository.deleteAll()
            foodRepository.deleteAll()
        }

        fun saveSent(name: String, sentAt: LocalDateTime, attempts: Int): FoodContentOutbox {
            val food = foodRepository.save(Food.failed(name))
            val outbox = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
            return outboxRepository.save(
                outbox.apply {
                    outboxStatus = FoodContentOutboxStatus.SENT
                    this.sentAt = sentAt
                    this.attempts = attempts
                },
            )
        }

        given("응답 없이 굳은 아웃박스") {
            `when`("보낸 지 기준 시간을 넘겼고 재시도 여유가 있으면") {
                then("다시 대기로 돌리고 보낸 시각을 비운다 — 기준 시간이 다시 지나야 재회수 대상이 된다") {
                    clear()
                    val stuck = saveSent("굳은국수", LocalDateTime.now().minusHours(30), attempts = 1)

                    val summary = recovery().recoverStale()

                    summary.requeued shouldBe 1
                    summary.dead shouldBe 0
                    val reloaded = outboxRepository.findById(stuck.id).orElseThrow()
                    reloaded.outboxStatus shouldBe FoodContentOutboxStatus.PENDING
                    reloaded.sentAt.shouldBeNull()
                    reloaded.lastError.shouldNotBeNull()

                    recovery().recoverStale().requeued shouldBe 0
                }
            }

            `when`("보낸 지 얼마 되지 않았으면") {
                then("건드리지 않는다") {
                    clear()
                    val fresh = saveSent("방금국수", LocalDateTime.now().minusHours(1), attempts = 1)

                    recovery().recoverStale().requeued shouldBe 0

                    outboxRepository.findById(fresh.id).orElseThrow().outboxStatus shouldBe FoodContentOutboxStatus.SENT
                }
            }

            `when`("재시도 상한에 닿았으면") {
                then("포기 시각과 사유를 남기고 더 보내지 않는다 — 숫자만 남기지 않는다") {
                    clear()
                    val exhausted = saveSent("포기국수", LocalDateTime.now().minusHours(30), attempts = 5)

                    val summary = recovery(maxAttempts = 5).recoverStale()

                    summary.dead shouldBe 1
                    val reloaded = outboxRepository.findById(exhausted.id).orElseThrow()
                    reloaded.deadAt.shouldNotBeNull()
                    reloaded.lastError!! shouldContain "5"
                    reloaded.outboxStatus shouldBe FoodContentOutboxStatus.SENT

                    recovery(maxAttempts = 5).recoverStale().dead shouldBe 0
                }
            }
        }
    }
}
