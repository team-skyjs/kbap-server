package com.kbap.api.food

import com.github.benmanes.caffeine.cache.Ticker
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class PopularFoodIdCacheTest : BehaviorSpec({

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

        `when`("로더가 예외를 던지면") {
            val calls = AtomicInteger()
            val cache = PopularFoodIdCache(FakeTicker()) {
                if (calls.incrementAndGet() == 1) throw IllegalStateException("집계 실패")
                listOf(7L)
            }

            then("예외가 전파되고 다음 조회가 로더를 다시 호출한다") {
                shouldThrow<IllegalStateException> { cache.getPopularFoodIds(10) }
                cache.getPopularFoodIds(10) shouldBe listOf(7L)
                calls.get() shouldBe 2
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
