package com.kbap.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.atomic.AtomicInteger

class BackgroundTasksTest : BehaviorSpec({
    fun executor(core: Int, max: Int, queue: Int) = ThreadPoolTaskExecutor().apply {
        corePoolSize = core
        maxPoolSize = max
        queueCapacity = queue
        initialize()
    }

    given("백그라운드 실행기 소진 대기") {
        `when`("큐가 무한이라 풀이 코어 수를 넘지 않는 실행기면") {
            then("앞서 제출된 작업이 모두 끝난 뒤에 돌아온다") {
                val pool = executor(core = 2, max = Int.MAX_VALUE, queue = Int.MAX_VALUE)
                val finished = AtomicInteger()
                try {
                    repeat(6) { pool.execute { Thread.sleep(50); finished.incrementAndGet() } }

                    BackgroundTasks.drain(pool)

                    finished.get() shouldBe 6
                } finally {
                    pool.shutdown()
                }
            }
        }

        `when`("큐가 유한하고 최대 크기가 코어보다 커서 큐가 차면 스레드가 늘어나는 실행기면") {
            then("명시적으로 실패한다 — 탐침이 새 스레드에서 먼저 돌면 앞선 작업이 남은 채 돌아오기 때문이다") {
                val pool = executor(core = 1, max = 4, queue = 1)
                try {
                    shouldThrow<IllegalStateException> { BackgroundTasks.drain(pool) }
                } finally {
                    pool.shutdown()
                }
            }
        }
    }
})
