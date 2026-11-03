package com.kbap.api.processedfood

import com.kbap.common.port.llm.ProcessedFoodPhotoKind
import com.kbap.common.port.llm.ProcessedFoodPhotoReader
import com.kbap.common.port.llm.ProcessedFoodPhotoReading
import com.kbap.common.port.llm.ProcessedFoodPhotoReason
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class FakeProcessedFoodPhotoReader : ProcessedFoodPhotoReader {
    private val byPath = ConcurrentHashMap<String, ProcessedFoodPhotoReading>()
    private val failures = ConcurrentHashMap<String, RuntimeException>()
    val calls = AtomicInteger()

    fun program(path: String, reading: ProcessedFoodPhotoReading) {
        byPath[path] = reading
        failures.remove(path)
    }

    fun failOn(path: String, error: RuntimeException) {
        failures[path] = error
    }

    fun reset() {
        byPath.clear()
        failures.clear()
        calls.set(0)
    }

    override fun read(imagePath: String): ProcessedFoodPhotoReading {
        calls.incrementAndGet()
        failures[imagePath]?.let { throw it }
        return byPath[imagePath] ?: ProcessedFoodPhotoReading(ProcessedFoodPhotoKind.OTHER, 0.5, ProcessedFoodPhotoReason.NOT_FOOD)
    }
}

@Configuration
class FakeProcessedFoodPhotoReaderConfig {
    @Bean
    @Primary
    fun fakeProcessedFoodPhotoReader(): FakeProcessedFoodPhotoReader = FakeProcessedFoodPhotoReader()
}
