package com.kbap.api.food

import com.github.benmanes.caffeine.cache.CacheLoader
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import com.github.benmanes.caffeine.cache.LoadingCache
import com.github.benmanes.caffeine.cache.Ticker
import com.kbap.common.domain.food.FoodJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

@Component
class PopularFoodIdCache internal constructor(
    ticker: Ticker,
    executor: Executor = ForkJoinPool.commonPool(),
    private val loader: (Int) -> List<Long>,
) {
    @Autowired
    constructor(foodRepository: FoodJpaRepository, transactionManager: PlatformTransactionManager) : this(
        Ticker.systemTicker(),
        ForkJoinPool.commonPool(),
        aggregating(foodRepository, TransactionTemplate(transactionManager).apply { isReadOnly = true }),
    )

    private val log = LoggerFactory.getLogger(javaClass)

    private val cache: LoadingCache<Int, Loaded> = Caffeine.newBuilder()
        .expireAfter(Expiry.writing<Int, Loaded> { _, loaded -> if (loaded.failed) FAILURE_TTL else TTL })
        .refreshAfterWrite(REFRESH_AFTER)
        .ticker(ticker)
        .executor(executor)
        .build(
            object : CacheLoader<Int, Loaded> {
                override fun load(key: Int): Loaded = runCatching { aggregate(key) }
                    .getOrElse { e ->
                        log.warn("홈 인기 음식 집계 실패 — 인기 섹션을 비우고 {}초 뒤 다시 집계한다", FAILURE_TTL.seconds, e)
                        Loaded(emptyList(), failed = true)
                    }

                override fun reload(key: Int, oldValue: Loaded): Loaded = runCatching { aggregate(key) }
                    .getOrElse { e ->
                        log.warn("홈 인기 음식 백그라운드 갱신 실패 — 이전 목록을 유지한다", e)
                        oldValue
                    }
            },
        )

    fun getPopularFoodIds(size: Int): List<Long> {
        cache.getIfPresent(size)?.let { return it.ids }
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "인기 음식 집계는 호출자의 트랜잭션 안에서 돌면 안 된다 — 캐시 조회를 트랜잭션 앞으로 옮겨라"
        }
        return cache.get(size)!!.ids
    }

    fun invalidateAll() = cache.invalidateAll()

    private fun aggregate(size: Int): Loaded {
        val started = System.nanoTime()
        val ids = loader(size)
        log.info("홈 인기 음식 집계 — 최근 {}일 조회 수 상위 {}개 요청, {}건 적재, {}ms", WINDOW_DAYS, size, ids.size, (System.nanoTime() - started) / 1_000_000)
        return Loaded(ids, failed = false)
    }

    internal class Loaded(val ids: List<Long>, val failed: Boolean)

    companion object {
        const val WINDOW_DAYS = 30L
        val TTL: Duration = Duration.ofDays(2)
        val REFRESH_AFTER: Duration = Duration.ofHours(6)
        val FAILURE_TTL: Duration = Duration.ofSeconds(30)

        private fun aggregating(foodRepository: FoodJpaRepository, readOnlyTransaction: TransactionTemplate): (Int) -> List<Long> = { size ->
            readOnlyTransaction.execute { foodRepository.findPopular(LocalDateTime.now().minusDays(WINDOW_DAYS), size).map { it.id } }!!
        }
    }
}
