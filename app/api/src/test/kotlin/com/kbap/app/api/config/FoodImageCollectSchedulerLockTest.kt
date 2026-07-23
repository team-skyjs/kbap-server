package com.kbap.app.api.config

import com.kbap.core.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// ShedLock + Flyway shedlock 테이블 통합 검증 — 같은 lock name 으로 동시에 실행해도 1회만 수행됨을 보장.
@SpringBootTest
@Import(MySqlContainerConfig::class)
class FoodImageCollectSchedulerLockTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var lockProvider: LockProvider

    init {
        fun lockConfig(name: String) =
            LockConfiguration(Instant.now(), name, Duration.ofMinutes(5), Duration.ofSeconds(30))

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

                    // 락 보유 중 두 번째 실행 — 선점 실패로 즉시 스킵되어야 한다
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
