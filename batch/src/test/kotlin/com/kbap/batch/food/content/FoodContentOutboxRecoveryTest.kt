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
import org.springframework.transaction.support.TransactionTemplate
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

            `when`("회수 직전에 지연된 응답이 도착해 완료로 바뀌었으면") {
                then("되살리지 않는다 — 낡은 스냅샷이 완료를 덮지 않게") {
                    clear()
                    val completed = saveSent("늦은응답국수", LocalDateTime.now().minusHours(30), attempts = 1)
                    outboxRepository.save(
                        completed.apply { outboxStatus = FoodContentOutboxStatus.COMPLETE },
                    )

                    TransactionTemplate(transactionManager).execute {
                        outboxRepository.requeueIfStillSent(completed.id, "테스트")
                    } shouldBe 0

                    outboxRepository.findById(completed.id).orElseThrow().outboxStatus shouldBe
                        FoodContentOutboxStatus.COMPLETE
                }
            }

            `when`("음식이 삭제된 뒤 굳은 행이면") {
                then("회수하지 않는다 — 다시 보내도 콜백이 음식을 못 찾고, 포기로 떨어져도 재수집할 대상이 없다") {
                    clear()
                    val food = foodRepository.save(Food.failed("삭제국수"))
                    val outbox = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
                    outboxRepository.save(
                        outbox.apply {
                            outboxStatus = FoodContentOutboxStatus.SENT
                            sentAt = LocalDateTime.now().minusHours(30)
                        },
                    )
                    foodRepository.save(food.apply { delete() })

                    val summary = recovery().recoverStale()

                    summary.requeued shouldBe 0
                    summary.dead shouldBe 0
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
