package com.kbap.api.scan

import com.kbap.common.port.llm.FoodImageGenerator
import com.kbap.common.port.llm.GeneratedFoodImage
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FakeFoodImageGenerator : FoodImageGenerator {
    val prompts: MutableList<String> = CopyOnWriteArrayList()
    val qualities: MutableList<String> = CopyOnWriteArrayList()

    @Volatile var gate: CountDownLatch? = null
    @Volatile var fail: Boolean = false

    fun reset() {
        prompts.clear()
        qualities.clear()
        gate = null
        fail = false
    }

    override fun generate(prompt: String, quality: String): GeneratedFoodImage {
        prompts += prompt
        qualities += quality
        gate?.await(10, TimeUnit.SECONDS)
        if (fail) error("fake image failure")
        return GeneratedFoodImage(bytes = byteArrayOf(1, 2, 3), inputTokens = 100, outputTokens = 1_000)
    }
}

@Configuration
class FakeFoodImageGeneratorConfig {
    @Bean
    @Primary
    fun fakeFoodImageGenerator(): FakeFoodImageGenerator = FakeFoodImageGenerator()
}
