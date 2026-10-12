package com.kbap.api.food

import com.github.benmanes.caffeine.cache.Ticker
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class PopularFoodIdCacheTest : BehaviorSpec({

    class DeferredExecutor : Executor {
        private val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending += command }
        fun runAll() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    class FakeTicker : Ticker {
        private val nanos = AtomicLong()
        override fun read(): Long = nanos.get()
        fun advance(duration: java.time.Duration) {
            nanos.addAndGet(duration.toNanos())
        }
    }

    given("인기 음식 id 캐시") {
        `when`("같은 크기로 두 번 조회하면") {
            val calls = AtomicInteger()
            val cache = PopularFoodIdCache(FakeTicker()) { size -> calls.incrementAndGet(); (1L..size).toList() }

            val first = cache.getPopularFoodIds(3)
            val second = cache.getPopularFoodIds(3)

            then("로더는 1회만 호출되고 두 결과가 같다") {
                calls.get() shouldBe 1
                first shouldBe second
                first shouldBe listOf(1L, 2L, 3L)
            }
        }

        `when`("TTL(2일)이 지난 뒤 조회하면") {
            val ticker = FakeTicker()
            val calls = AtomicInteger()
            val cache = PopularFoodIdCache(ticker) { listOf(calls.incrementAndGet().toLong()) }

            val before = cache.getPopularFoodIds(10)
            ticker.advance(PopularFoodIdCache.TTL)
            val after = cache.getPopularFoodIds(10)

            then("로더를 다시 호출해 새 목록을 돌려준다") {
                calls.get() shouldBe 2
                before shouldBe listOf(1L)
                after shouldBe listOf(2L)
            }
        }

        `when`("빈 캐시에서 로더가 예외를 던지면") {
            val ticker = FakeTicker()
            val calls = AtomicInteger()
            val cache = PopularFoodIdCache(ticker) {
                if (calls.incrementAndGet() == 1) throw IllegalStateException("집계 실패")
                listOf(7L)
            }

            then("빈 목록을 돌려주고(홈 500 금지), 실패는 30초 동안 캐시돼 재집계 폭주를 막는다") {
                cache.getPopularFoodIds(10) shouldBe emptyList()
                cache.getPopularFoodIds(10) shouldBe emptyList()
                calls.get() shouldBe 1

                ticker.advance(PopularFoodIdCache.FAILURE_TTL.plusSeconds(1))

                cache.getPopularFoodIds(10) shouldBe listOf(7L)
                calls.get() shouldBe 2
            }
        }

        `when`("갱신 주기(6시간)가 지난 뒤 조회하면") {
            val ticker = FakeTicker()
            val background = DeferredExecutor()
            val calls = AtomicInteger()
            val cache = PopularFoodIdCache(ticker, background) { listOf(calls.incrementAndGet().toLong()) }

            val fresh = cache.getPopularFoodIds(10)
            ticker.advance(PopularFoodIdCache.REFRESH_AFTER.plusMinutes(1))
            val staleWhileRefreshing = cache.getPopularFoodIds(10)
            val callsBeforeBackground = calls.get()
            background.runAll()
            val callsAfterBackground = calls.get()
            val refreshed = cache.getPopularFoodIds(10)

            then("이전 목록을 즉시 돌려주고(호출 스레드는 집계하지 않음) 백그라운드가 1회 갱신해 다음 조회부터 새 목록이다") {
                fresh shouldBe listOf(1L)
                staleWhileRefreshing shouldBe listOf(1L)
                callsBeforeBackground shouldBe 1
                callsAfterBackground shouldBe 2
                refreshed shouldBe listOf(2L)
                calls.get() shouldBe 2
            }
        }

        `when`("백그라운드 갱신에서 로더가 예외를 던지면") {
            val ticker = FakeTicker()
            val background = DeferredExecutor()
            val calls = AtomicInteger()
            val cache = PopularFoodIdCache(ticker, background) {
                if (calls.incrementAndGet() == 2) throw IllegalStateException("집계 실패")
                listOf(calls.get().toLong())
            }

            cache.getPopularFoodIds(10)
            ticker.advance(PopularFoodIdCache.REFRESH_AFTER.plusMinutes(1))
            cache.getPopularFoodIds(10)
            background.runAll()
            val callsAfterFailedRefresh = calls.get()
            background.runAll()
            val callsAfterIdleBackground = calls.get()
            val afterFailedRefresh = cache.getPopularFoodIds(10)
            ticker.advance(PopularFoodIdCache.TTL.minus(PopularFoodIdCache.REFRESH_AFTER))
            val afterOriginalTtl = cache.getPopularFoodIds(10)

            then("이전 목록을 유지하되(빈 목록으로 바꾸지 않음) 스스로 재시도하지 않고, 원래 적재 시각 기준 2일 상한은 그대로라 만료 뒤 다시 집계한다") {
                callsAfterFailedRefresh shouldBe 2
                callsAfterIdleBackground shouldBe 2
                afterFailedRefresh shouldBe listOf(1L)
                afterOriginalTtl shouldBe listOf(3L)
                calls.get() shouldBe 3
            }
        }

        `when`("트랜잭션 안에서 조회하면") {
            val calls = AtomicInteger()
            val cache = PopularFoodIdCache(FakeTicker()) { calls.incrementAndGet(); listOf(1L) }

            then("캐시가 비었든 따뜻하든 불변식 위반으로 거절한다 — 회귀 호출자가 콜드 때만 터지지 않게") {
                TransactionSynchronizationManager.setActualTransactionActive(true)
                try {
                    shouldThrow<IllegalStateException> { cache.getPopularFoodIds(10) }
                } finally {
                    TransactionSynchronizationManager.setActualTransactionActive(false)
                }
                calls.get() shouldBe 0

                cache.getPopularFoodIds(10) shouldBe listOf(1L)
                TransactionSynchronizationManager.setActualTransactionActive(true)
                try {
                    shouldThrow<IllegalStateException> { cache.getPopularFoodIds(10) }
                } finally {
                    TransactionSynchronizationManager.setActualTransactionActive(false)
                }
                calls.get() shouldBe 1
            }
        }

        `when`("로더가 빈 목록을 돌려주면") {
            val calls = AtomicInteger()
            val cache = PopularFoodIdCache(FakeTicker()) { calls.incrementAndGet(); emptyList() }

            cache.getPopularFoodIds(10)
            val second = cache.getPopularFoodIds(10)

            then("빈 목록이 캐시돼 두 번째 조회는 로더를 부르지 않는다") {
                second shouldBe emptyList()
                calls.get() shouldBe 1
            }
        }
    }

    given("빈 캐시에 동시 요청") {
        `when`("50개 스레드가 동시에 조회하면") {
            val calls = AtomicInteger()
            val cache = PopularFoodIdCache(FakeTicker()) {
                Thread.sleep(200)
                calls.incrementAndGet()
                listOf(1L, 2L)
            }
            val start = CountDownLatch(1)

            val results = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val futures = (1..50).map {
                    executor.submit<List<Long>> {
                        start.await()
                        cache.getPopularFoodIds(10)
                    }
                }
                start.countDown()
                futures.map { it.get() }
            }

            then("로더는 1회만 호출되고 모든 결과가 같다") {
                calls.get() shouldBe 1
                results.toSet().size shouldBe 1
            }
        }
    }
})
