package com.kbap.api.food

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import com.kbap.common.domain.food.FoodJpaRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Component
class PopularFoodIdCache internal constructor(ticker: Ticker, private val loader: (Int) -> List<Long>) {

    @Autowired
    constructor(foodRepository: FoodJpaRepository) : this(
        Ticker.systemTicker(),
        { size -> foodRepository.findPopular(LocalDateTime.now().minusDays(WINDOW_DAYS), size).map { it.id } },
    )

    private val log = LoggerFactory.getLogger(javaClass)
    private val cache: Cache<Int, List<Long>> = Caffeine.newBuilder().expireAfterWrite(TTL).ticker(ticker).build()
    private val refresh = ReentrantLock()

    fun getPopularFoodIds(size: Int): List<Long> {
        cache.getIfPresent(size)?.let { return it }
        return refresh.withLock { cache.getIfPresent(size) ?: load(size).also { cache.put(size, it) } }
    }

    internal fun waitingRequests(): Int = refresh.queueLength

    private fun load(size: Int): List<Long> {
        val started = System.nanoTime()
        val ids = loader(size)
        log.info("홈 인기 음식 집계 — 최근 {}일 조회 수 상위 {}개 요청, {}건 적재, {}ms", WINDOW_DAYS, size, ids.size, (System.nanoTime() - started) / 1_000_000)
        return ids
    }

    fun invalidateAll() = cache.invalidateAll()

    companion object {
        const val WINDOW_DAYS = 30L
        val TTL: Duration = Duration.ofDays(2)
    }
}
