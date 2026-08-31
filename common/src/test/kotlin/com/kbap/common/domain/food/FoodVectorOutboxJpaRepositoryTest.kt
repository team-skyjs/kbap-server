package com.kbap.common.domain.food

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class FoodVectorOutboxJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var outboxRepository: FoodVectorOutboxJpaRepository

    init {
        fun clear() {
            outboxRepository.deleteAll()
            foodRepository.deleteAll()
        }

        fun saveFood(koreanName: String): Food = foodRepository.save(Food.failed(koreanName))

        fun reload(outbox: FoodVectorOutbox): FoodVectorOutbox =
            outboxRepository.findById(outbox.id).orElseThrow()

        given("동기화 대상 조회") {
            `when`("마지막으로 확인한 id 와 조회 개수를 지정하면") {
                then("커서 다음의 대기 건만 id 오름차순으로 제한된 개수만큼 나온다") {
                    clear()
                    val first = saveFood("들깨칼국수")
                    val second = saveFood("콩국수")
                    val third = saveFood("잔치국수")
                    val firstOutbox = outboxRepository.save(FoodVectorOutbox.upsert(first.id))
                    outboxRepository.save(FoodVectorOutbox.upsert(second.id))
                    outboxRepository.save(FoodVectorOutbox.upsert(third.id))

                    outboxRepository.findPendingAfterId(firstOutbox.id, 1).map { it.foodId } shouldBe
                        listOf(second.id)
                }
            }

            `when`("완료·실패 건이 섞여 있으면") {
                then("대기 건만 나온다") {
                    clear()
                    val pendingFood = saveFood("비빔국수")
                    val completedFood = saveFood("수제비")
                    val failedFood = saveFood("칼국수")
                    outboxRepository.save(FoodVectorOutbox.upsert(pendingFood.id))
                    val completed = FoodVectorOutbox.upsert(completedFood.id).apply { complete() }
                    outboxRepository.save(completed)
                    val failed = FoodVectorOutbox.upsert(failedFood.id).apply {
                        repeat(FoodVectorOutbox.MAX_ATTEMPTS) { recordFailure("임베딩 실패") }
                    }
                    outboxRepository.save(failed)

                    outboxRepository.findPendingAfterId(0, 10).map { it.foodId } shouldBe listOf(pendingFood.id)
                }
            }
        }

        given("중복 생성 억제 판정") {
            `when`("같은 음식의 같은 작업이 이미 대기 중이면") {
                then("존재로 판정한다") {
                    clear()
                    val food = saveFood("감자전")
                    outboxRepository.save(FoodVectorOutbox.upsert(food.id))

                    outboxRepository.existsByFoodIdAndOperationAndOutboxStatus(
                        food.id,
                        FoodVectorOutboxOperation.UPSERT,
                        FoodVectorOutboxStatus.PENDING,
                    ) shouldBe true
                }
            }

            `when`("같은 음식이라도 작업 종류가 다르면") {
                then("존재하지 않는 것으로 판정한다 — 적재와 제거는 서로를 억제하지 않는다") {
                    clear()
                    val food = saveFood("김치전")
                    outboxRepository.save(FoodVectorOutbox.upsert(food.id))

                    outboxRepository.existsByFoodIdAndOperationAndOutboxStatus(
                        food.id,
                        FoodVectorOutboxOperation.DELETE,
                        FoodVectorOutboxStatus.PENDING,
                    ) shouldBe false
                }
            }
        }

        given("처리 결과 기록") {
            `when`("대기 건을 완료하면") {
                then("완료 상태로 저장된다") {
                    clear()
                    val food = saveFood("된장찌개")
                    val outbox = outboxRepository.save(FoodVectorOutbox.upsert(food.id))

                    outbox.complete()
                    outboxRepository.save(outbox)

                    reload(outbox).outboxStatus shouldBe FoodVectorOutboxStatus.COMPLETE
                }
            }

            `when`("처리에 실패하면") {
                then("시도 횟수가 늘고 마지막 실패 원인이 남으며 대기 상태를 유지한다") {
                    clear()
                    val food = saveFood("순두부찌개")
                    val outbox = outboxRepository.save(FoodVectorOutbox.upsert(food.id))

                    outbox.recordFailure("긴 설명이 비어 있습니다")
                    outboxRepository.save(outbox)

                    val reloaded = reload(outbox)
                    reloaded.attempts shouldBe 1
                    reloaded.lastError shouldBe "긴 설명이 비어 있습니다"
                    reloaded.outboxStatus shouldBe FoodVectorOutboxStatus.PENDING
                }
            }

            `when`("실패 원인이 컬럼 길이를 넘으면") {
                then("컬럼 길이까지만 잘라 저장한다 — 저장 실패로 실패 기록 자체를 잃지 않는다") {
                    clear()
                    val food = saveFood("청국장")
                    val outbox = outboxRepository.save(FoodVectorOutbox.upsert(food.id))

                    outbox.recordFailure("가".repeat(FoodVectorOutbox.MAX_LAST_ERROR_LENGTH + 100))
                    outboxRepository.save(outbox)

                    reload(outbox).lastError shouldBe "가".repeat(FoodVectorOutbox.MAX_LAST_ERROR_LENGTH)
                }
            }

            `when`("실패가 최대 시도 횟수에 도달하면") {
                then("실패 상태로 격리한다 — 다음 배치가 같은 실패를 반복하지 않는다") {
                    clear()
                    val food = saveFood("부대찌개")
                    val outbox = outboxRepository.save(FoodVectorOutbox.upsert(food.id))

                    repeat(FoodVectorOutbox.MAX_ATTEMPTS) { outbox.recordFailure("임베딩 호출 실패") }
                    outboxRepository.save(outbox)

                    val reloaded = reload(outbox)
                    reloaded.attempts shouldBe FoodVectorOutbox.MAX_ATTEMPTS
                    reloaded.outboxStatus shouldBe FoodVectorOutboxStatus.FAILED
                }
            }

            `when`("실패 격리된 건을 관리자가 재처리하면") {
                then("시도 횟수를 초기화한 대기 상태로 돌아가고 실패 원인은 남는다") {
                    clear()
                    val food = saveFood("김치찌개")
                    val outbox = outboxRepository.save(FoodVectorOutbox.upsert(food.id))
                    repeat(FoodVectorOutbox.MAX_ATTEMPTS) { outbox.recordFailure("임베딩 호출 실패") }
                    outboxRepository.save(outbox)

                    outbox.retry()
                    outboxRepository.save(outbox)

                    val reloaded = reload(outbox)
                    reloaded.outboxStatus shouldBe FoodVectorOutboxStatus.PENDING
                    reloaded.attempts shouldBe 0
                    reloaded.lastError shouldBe "임베딩 호출 실패"
                    outboxRepository.findPendingAfterId(0, 10).map { it.id } shouldBe listOf(outbox.id)
                }
            }
        }

        given("운영 대시보드 조회") {
            `when`("상태별 건수를 세면") {
                then("상태마다 해당 건수만 나온다") {
                    clear()
                    val first = saveFood("갈비탕")
                    val second = saveFood("설렁탕")
                    val third = saveFood("육개장")
                    outboxRepository.save(FoodVectorOutbox.upsert(first.id))
                    outboxRepository.save(FoodVectorOutbox.upsert(second.id))
                    val failed = FoodVectorOutbox.upsert(third.id).apply {
                        repeat(FoodVectorOutbox.MAX_ATTEMPTS) { recordFailure("임베딩 호출 실패") }
                    }
                    outboxRepository.save(failed)

                    outboxRepository.countByOutboxStatus(FoodVectorOutboxStatus.PENDING) shouldBe 2
                    outboxRepository.countByOutboxStatus(FoodVectorOutboxStatus.FAILED) shouldBe 1
                }
            }

            `when`("실패 목록을 조회하면") {
                then("실패 건만 최신순으로 나온다") {
                    clear()
                    val first = saveFood("냉면")
                    val second = saveFood("막국수")
                    val third = saveFood("쫄면")
                    val olderFailure = FoodVectorOutbox.upsert(first.id).apply {
                        repeat(FoodVectorOutbox.MAX_ATTEMPTS) { recordFailure("문서 저장 실패") }
                    }
                    outboxRepository.save(olderFailure)
                    outboxRepository.save(FoodVectorOutbox.upsert(second.id))
                    val newerFailure = FoodVectorOutbox.upsert(third.id).apply {
                        repeat(FoodVectorOutbox.MAX_ATTEMPTS) { recordFailure("문서 저장 실패") }
                    }
                    outboxRepository.save(newerFailure)

                    val failures = outboxRepository.findTop20ByOutboxStatusOrderByIdDesc(
                        FoodVectorOutboxStatus.FAILED,
                    )

                    failures.map { it.foodId } shouldBe listOf(third.id, first.id)
                    failures.first().lastError shouldNotBe null
                }
            }
        }
    }
}
