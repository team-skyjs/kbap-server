package com.kbap.api

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object BackgroundTasks {
    fun drain(executor: ThreadPoolTaskExecutor) {
        val workers = executor.corePoolSize
        val occupied = CountDownLatch(workers)
        val release = CountDownLatch(1)
        val probes = (1..workers).map {
            executor.submit {
                occupied.countDown()
                release.await()
            }
        }
        try {
            check(occupied.await(60, TimeUnit.SECONDS)) { "백그라운드 실행기가 60초 안에 비지 않았다" }
        } finally {
            release.countDown()
        }
        probes.forEach { it.get() }
    }
}
