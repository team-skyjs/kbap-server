package com.kbap.api.home

import com.kbap.api.IntegrationTest
import com.kbap.api.bookmark.BookmarkService
import com.kbap.api.food.FoodService
import com.kbap.api.food.PopularFoodIdCache
import com.kbap.api.ingredient.IngredientService
import com.kbap.api.member.MemberService
import com.kbap.api.review.ReviewService
import com.github.benmanes.caffeine.cache.Ticker
import com.kbap.common.domain.LanguageCode
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@IntegrationTest
class HomePopularCacheLoadTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var memberService: MemberService
    @Autowired private lateinit var ingredientService: IngredientService
    @Autowired private lateinit var foodService: FoodService
    @Autowired private lateinit var bookmarkService: BookmarkService
    @Autowired private lateinit var reviewService: ReviewService
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    init {
        fun activeConnections(): Int = dataSource.unwrap(HikariDataSource::class.java).hikariPoolMXBean.activeConnections

        fun awaitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                if (condition()) return true
                Thread.sleep(10)
            }
            return condition()
        }

        beforeContainer { HomeTestSeed.reset(dataSource) }
        afterSpec { HomeTestSeed.reset(dataSource) }

        given("빈 인기 캐시에 홈 요청이 몰릴 때") {
            `when`("첫 요청이 집계 중이고 나머지 7개가 같은 캐시 키를 기다리면") {
                then("기다리는 요청은 DB 연결을 쥐지 않고(active 0), 집계는 트랜잭션 밖에서 1회만 돈 뒤 8개 전부 같은 목록을 받는다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 2)
                    val loaderEntered = CountDownLatch(1)
                    val releaseLoader = CountDownLatch(1)
                    val loads = AtomicInteger()
                    val loadedInTransaction = AtomicBoolean(true)
                    val cache = PopularFoodIdCache(Ticker.systemTicker()) { _ ->
                        loads.incrementAndGet()
                        loadedInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive())
                        loaderEntered.countDown()
                        releaseLoader.await(30, TimeUnit.SECONDS)
                        listOf(1L, 2L)
                    }
                    val homeService = HomeService(memberService, ingredientService, foodService, bookmarkService, reviewService, cache, transactionManager)
                    val requests = 8
                    val pool = Executors.newFixedThreadPool(requests)
                    try {
                        val futures = (1..requests).map { pool.submit(Callable { homeService.getHome(null, LanguageCode.EN) }) }

                        loaderEntered.await(30, TimeUnit.SECONDS) shouldBe true
                        awaitUntil(10_000) { cache.waitingRequests() == requests - 1 } shouldBe true
                        val activeWhileWaiting = (1..20).maxOf { Thread.sleep(10); activeConnections() }
                        releaseLoader.countDown()

                        val results = futures.map { it.get(60, TimeUnit.SECONDS) }
                        activeWhileWaiting shouldBe 0
                        loads.get() shouldBe 1
                        loadedInTransaction.get() shouldBe false
                        results.map { home -> home.popularFoods.map { it.foodId } }.distinct() shouldBe listOf(listOf(1L, 2L))
                    } finally {
                        releaseLoader.countDown()
                        pool.shutdownNow()
                    }
                }
            }
        }
    }
}
