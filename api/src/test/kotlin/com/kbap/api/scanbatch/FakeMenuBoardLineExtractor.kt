package com.kbap.api.scanbatch

import com.kbap.common.port.llm.MenuBoardLine
import com.kbap.common.port.llm.MenuBoardLineExtractor
import com.kbap.common.port.llm.MenuBoardVisionUnavailableException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class FakeMenuBoardLineExtractor : MenuBoardLineExtractor {
    private val byPath = ConcurrentHashMap<String, List<MenuBoardLine>>()
    private val failing = ConcurrentHashMap.newKeySet<String>()
    private val unavailable = ConcurrentHashMap.newKeySet<String>()
    val calls = AtomicInteger()
    private val running = AtomicInteger()
    val maxConcurrent = AtomicInteger()

    @Volatile
    var delayMillis: Long = 0

    fun program(path: String, lines: List<MenuBoardLine>) {
        failing.remove(path)
        unavailable.remove(path)
        byPath[path] = lines
    }

    fun failOn(path: String) {
        failing.add(path)
    }

    fun unavailableOn(path: String) {
        unavailable.add(path)
    }

    fun reset() {
        byPath.clear()
        failing.clear()
        unavailable.clear()
        calls.set(0)
        maxConcurrent.set(0)
        delayMillis = 0
    }

    override fun extractLines(imagePath: String): List<MenuBoardLine> {
        calls.incrementAndGet()
        val now = running.incrementAndGet()
        maxConcurrent.accumulateAndGet(now, ::maxOf)
        try {
            if (delayMillis > 0) Thread.sleep(delayMillis)
            if (imagePath in unavailable) throw MenuBoardVisionUnavailableException(RuntimeException("장애(테스트)"))
            if (imagePath in failing) throw RuntimeException("인식 실패(테스트)")
            return byPath[imagePath] ?: emptyList()
        } finally {
            running.decrementAndGet()
        }
    }
}

@Configuration
class FakeLineExtractorConfig {
    @Bean
    fun fakeMenuBoardLineExtractor(): FakeMenuBoardLineExtractor = FakeMenuBoardLineExtractor()
}
