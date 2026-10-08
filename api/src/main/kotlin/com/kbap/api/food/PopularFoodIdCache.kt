package com.kbap.api.food

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import com.kbap.common.domain.food.FoodJpaRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
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

    private val cache: Cache<Int, List<Long>> = Caffeine.newBuilder().expireAfterWrite(TTL).ticker(ticker).build()
    private val refresh = ReentrantLock()

    fun getPopularFoodIds(size: Int): List<Long> {
        cache.getIfPresent(size)?.let { return it }
        return refresh.withLock { cache.getIfPresent(size) ?: loader(size).also { cache.put(size, it) } }
    }

    fun invalidateAll() = cache.invalidateAll()

    companion object {
        const val WINDOW_DAYS = 30L
        val TTL: Duration = Duration.ofDays(2)
    }
}
