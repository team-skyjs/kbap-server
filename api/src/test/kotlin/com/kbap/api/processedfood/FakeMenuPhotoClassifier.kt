package com.kbap.api.processedfood

import com.kbap.common.port.llm.MenuPhotoClassifier
import com.kbap.common.port.llm.MenuPhotoVerdict
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class FakeMenuPhotoClassifier : MenuPhotoClassifier {
    private val answers = ConcurrentHashMap<String, () -> MenuPhotoVerdict>()
    val calls = AtomicInteger()
    val interrupted = AtomicInteger()

    fun program(path: String, answer: () -> MenuPhotoVerdict) {
        answers[path] = answer
    }

    fun reset() {
        answers.clear()
        calls.set(0)
        interrupted.set(0)
    }

    override fun classify(imagePath: String): MenuPhotoVerdict {
        calls.incrementAndGet()
        return try {
            (answers[imagePath] ?: { MenuPhotoVerdict.NotMenu })()
        } catch (e: InterruptedException) {
            interrupted.incrementAndGet()
            throw e
        }
    }
}

@Configuration
class FakeMenuPhotoClassifierConfig {
    @Bean
    @Primary
    fun fakeMenuPhotoClassifier(): FakeMenuPhotoClassifier = FakeMenuPhotoClassifier()
}
