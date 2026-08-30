package com.kbap.api.core.config

import com.kbap.api.IntegrationTest
import com.kbap.api.food.FoodImageBatchCollectService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@IntegrationTest
class ShedLockProviderTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var lockProvider: LockProvider

    @Autowired
    private lateinit var collectService: FoodImageBatchCollectService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    init {
        fun lockConfig(name: String) =
            LockConfiguration(Instant.now(), name, Duration.ofMinutes(5), Duration.ofSeconds(30))

        given("@SchedulerLock AOP 배선 — 이미지 회수 스케줄 진입점") {
            `when`("collectOnSchedule 을 호출하면") {
                then("shedlock 테이블에 food-image-collect 락이 남는다 — 어노테이션이 실제로 감싸고 있다") {
                    collectService.collectOnSchedule()

                    jdbcTemplate.queryForObject(
                        "select count(*) from shedlock where name = 'food-image-collect'",
                        Int::class.java,
                    ) shouldBe 1
                }
            }
        }

        given("ShedLock JDBC 락 — api 2대 동시 틱 시뮬레이션") {
            `when`("두 실행자가 같은 lock name 으로 동시에 실행하면") {
                then("한쪽만 수행되고 다른 쪽은 조용히 스킵된다") {
                    val executor1 = DefaultLockingTaskExecutor(lockProvider)
                    val executor2 = DefaultLockingTaskExecutor(lockProvider)
                    val executed = AtomicInteger(0)
                    val firstStarted = CountDownLatch(1)
                    val release = CountDownLatch(1)

                    val holder = thread {
                        executor1.executeWithLock(
                            Runnable {
                                executed.incrementAndGet()
                                firstStarted.countDown()
                                release.await()
                            },
                            lockConfig("lock-동시실행"),
                        )
                    }
                    firstStarted.await()

                    executor2.executeWithLock(Runnable { executed.incrementAndGet() }, lockConfig("lock-동시실행"))
                    release.countDown()
                    holder.join()

                    executed.get() shouldBe 1
                }
            }

            `when`("락 이름이 다르면") {
                then("서로 간섭하지 않고 각각 실행된다") {
                    val executor = DefaultLockingTaskExecutor(lockProvider)
                    val executed = AtomicInteger(0)

                    executor.executeWithLock(Runnable { executed.incrementAndGet() }, lockConfig("lock-A"))
                    executor.executeWithLock(Runnable { executed.incrementAndGet() }, lockConfig("lock-B"))

                    executed.get() shouldBe 2
                }
            }
        }
    }
}
