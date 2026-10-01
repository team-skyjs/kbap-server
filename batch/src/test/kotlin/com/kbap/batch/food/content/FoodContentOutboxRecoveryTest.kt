package com.kbap.batch.food.content

import com.kbap.batch.BatchIntegrationTest
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.port.mq.FoodContentPublishResult
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

        fun recovery(maxAttempts: Int = 5, maxPerRun: Int = 50) =
            FoodContentOutboxRecovery(outboxRepository, foodRepository, transactionManager, staleAfter, maxAttempts, maxPerRun)

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

            `when`("회수된 행이 다시 발행되면") {
                then("시도 횟수가 올라간다 — 상한이 실제로 닿는다") {
                    clear()
                    val stuck = saveSent("횟수국수", LocalDateTime.now().minusHours(30), attempts = 1)

                    recovery().recoverStale().requeued shouldBe 1
                    FoodContentOutboxPublisher(
                        outboxRepository,
                        { events -> FoodContentPublishResult(succeededOutboxIds = events.map { it.outboxId }.toSet(), failedOutboxIds = emptySet()) },
                        transactionManager,
                        10,
                    ).publishAll()

                    val reloaded = outboxRepository.findById(stuck.id).orElseThrow()
                    reloaded.attempts shouldBe 2
                    reloaded.outboxStatus shouldBe FoodContentOutboxStatus.SENT
                    reloaded.sentAt.shouldNotBeNull()
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
                        outboxRepository.requeueIfStillStale(completed.id, LocalDateTime.now(), "테스트")
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

            `when`("회수 읽기 뒤에 음식이 삭제됐으면") {
                then("음식 행 잠금 뒤의 재판정이 걸러 되살리지 않는다 — 대상 조회와 재판정이 같은 조건(STALE_SENT)을 본다") {
                    clear()
                    val food = foodRepository.save(Food.failed("읽고삭제국수"))
                    val outbox = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
                    outboxRepository.save(
                        outbox.apply {
                            outboxStatus = FoodContentOutboxStatus.SENT
                            sentAt = LocalDateTime.now().minusHours(30)
                        },
                    )
                    foodRepository.save(food.apply { delete() })

                    outboxRepository.countStillStale(outbox.id, LocalDateTime.now().minusHours(24)) shouldBe 0
                    recovery().recoverStale().requeued shouldBe 0
                    outboxRepository.findById(outbox.id).orElseThrow().outboxStatus shouldBe FoodContentOutboxStatus.SENT
                }
            }

            `when`("굳은 요청이 실행당 상한보다 많으면") {
                then("상한만큼만 되살리고 나머지는 다음 실행이 이어 받는다 — 첫 실행이 전부를 한꺼번에 재발행하지 않는다") {
                    clear()
                    repeat(7) { saveSent("버스트국수$it", LocalDateTime.now().minusHours(30), attempts = 1) }

                    recovery(maxPerRun = 5).recoverStale().requeued shouldBe 5
                    outboxRepository.countByOutboxStatus(FoodContentOutboxStatus.SENT) shouldBe 2
                    recovery(maxPerRun = 5).recoverStale().requeued shouldBe 2
                    recovery(maxPerRun = 5).recoverStale().requeued shouldBe 0
                }
            }

            `when`("콜백이 음식 행을 잠그고 완료 처리하는 동안 회수가 돌면") {
                then("회수는 그 뒤로 직렬화돼 완료된 요청을 되살리지 않는다 — 교착 없음(잠금 순서 food 행 → 아웃박스 행)") {
                    clear()
                    val stuck = saveSent("콜백교차국수", LocalDateTime.now().minusHours(30), attempts = 1)
                    val locked = java.util.concurrent.CountDownLatch(1)
                    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                    val callback = executor.submit {
                        TransactionTemplate(transactionManager).execute {
                            foodRepository.findByIdForUpdate(stuck.foodId)
                            outboxRepository.completeIfProcessable(stuck.id, stuck.foodId)
                            locked.countDown()
                            Thread.sleep(1_500)
                        }
                    }
                    executor.shutdown()
                    if (!locked.await(30, java.util.concurrent.TimeUnit.SECONDS)) callback.get(1, java.util.concurrent.TimeUnit.SECONDS)

                    val summary = recovery().recoverStale()
                    callback.get(30, java.util.concurrent.TimeUnit.SECONDS)

                    summary.requeued shouldBe 0
                    summary.dead shouldBe 0
                    outboxRepository.findById(stuck.id).orElseThrow().outboxStatus shouldBe FoodContentOutboxStatus.COMPLETE
                }
            }

            `when`("같은 음식에 더 새 요청이 생겼으면") {
                then("옛 굳은 행은 회수하지 않는다 — 재수집이 이미 새 행을 만들었다") {
                    clear()
                    val food = foodRepository.save(Food.failed("재수집국수"))
                    val old = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
                    outboxRepository.save(
                        old.apply {
                            outboxStatus = FoodContentOutboxStatus.SENT
                            sentAt = LocalDateTime.now().minusHours(30)
                        },
                    )
                    outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))

                    recovery().recoverStale().requeued shouldBe 0
                }
            }

            `when`("포기 처리된 행에 늦은 콜백이 도착하면") {
                then("완료로 살아나지 않는다 — 재수집이 만든 새 행만 유효하다") {
                    clear()
                    val food = foodRepository.save(Food.failed("늦은콜백국수"))
                    val outbox = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
                    outboxRepository.save(
                        outbox.apply {
                            outboxStatus = FoodContentOutboxStatus.SENT
                            sentAt = LocalDateTime.now().minusHours(30)
                            markDead("테스트 포기")
                        },
                    )

                    TransactionTemplate(transactionManager).execute {
                        outboxRepository.completeIfProcessable(outbox.id, food.id)
                    } shouldBe 0

                    outboxRepository.findById(outbox.id).orElseThrow().outboxStatus shouldBe
                        FoodContentOutboxStatus.SENT
                }
            }

            `when`("음식이 이미 공개(READY)됐으면") {
                then("재전송하지 않고 포기로 남긴다 — 결과가 오면 검수 없이 공개 콘텐츠를 덮어쓰므로 사람이 다시 판단한다") {
                    clear()
                    val stuck = saveSent("공개국수", LocalDateTime.now().minusHours(30), attempts = 1)
                    foodRepository.save(foodRepository.findById(stuck.foodId).orElseThrow().apply { contentStatus = FoodContentStatus.READY })

                    val summary = recovery().recoverStale()

                    summary.requeued shouldBe 0
                    summary.dead shouldBe 1
                    val reloaded = outboxRepository.findById(stuck.id).orElseThrow()
                    reloaded.outboxStatus shouldBe FoodContentOutboxStatus.SENT
                    reloaded.deadAt.shouldNotBeNull()
                    reloaded.lastError!! shouldContain "READY"
                }
            }

            `when`("음식이 검수 대기(PENDING_REVIEW)면") {
                then("종전대로 재전송한다 — 결과가 와도 검수를 거친다") {
                    clear()
                    val stuck = saveSent("검수국수", LocalDateTime.now().minusHours(30), attempts = 1)
                    foodRepository.save(foodRepository.findById(stuck.foodId).orElseThrow().apply { contentStatus = FoodContentStatus.PENDING_REVIEW })

                    recovery().recoverStale().requeued shouldBe 1
                    outboxRepository.findById(stuck.id).orElseThrow().deadAt.shouldBeNull()
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
