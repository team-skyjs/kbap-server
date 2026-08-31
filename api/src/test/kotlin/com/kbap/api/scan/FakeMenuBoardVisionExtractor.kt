package com.kbap.api.scan

import com.kbap.common.port.llm.ExtractedMenu
import com.kbap.common.port.llm.MenuBoardVisionExtractor
import com.kbap.common.port.llm.MenuBoardVisionQuotaExhaustedException
import com.kbap.common.port.llm.MenuBoardVisionRateLimitedException
import com.kbap.common.port.llm.MenuBoardVisionUnavailableException
import com.kbap.common.port.llm.OcrItem
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class FakeMenuBoardVisionExtractor : MenuBoardVisionExtractor {
    private val byPath = mutableMapOf<String, List<ExtractedMenu>>()
    private val failPaths = mutableSetOf<String>()
    private val unavailablePaths = mutableSetOf<String>()
    private val rateLimitedPaths = mutableMapOf<String, Long?>()
    private val quotaExhaustedPaths = mutableSetOf<String>()
    val receivedOcrItems: MutableMap<String, List<OcrItem>> = mutableMapOf()

    fun program(path: String, menus: List<ExtractedMenu>) {
        byPath[path] = menus
    }

    fun failOn(path: String) {
        failPaths.add(path)
    }

    fun unavailableOn(path: String) {
        unavailablePaths.add(path)
    }

    fun rateLimitedOn(path: String, retryAfterSeconds: Long? = null) {
        rateLimitedPaths[path] = retryAfterSeconds
    }

    fun quotaExhaustedOn(path: String) {
        quotaExhaustedPaths.add(path)
    }

    override fun extract(imagePath: String, ocrItems: List<OcrItem>): List<ExtractedMenu> {
        receivedOcrItems[imagePath] = ocrItems
        if (imagePath in unavailablePaths) {
            throw MenuBoardVisionUnavailableException(RuntimeException("LLM 서버 장애(테스트)"))
        }
        if (imagePath in rateLimitedPaths) {
            throw MenuBoardVisionRateLimitedException(
                retryAfterSeconds = rateLimitedPaths.getValue(imagePath),
                exhausted = false,
                limits = "remaining-requests=0",
                cause = RuntimeException("rate-limit(테스트)"),
            )
        }
        if (imagePath in quotaExhaustedPaths) {
            throw MenuBoardVisionQuotaExhaustedException("insufficient_quota", RuntimeException("quota(테스트)"))
        }
        if (imagePath in failPaths) throw RuntimeException("vision 인식 실패(테스트)")
        return byPath[imagePath] ?: emptyList()
    }
}

@Configuration
class FakeVisionConfig {
    @Bean
    fun fakeMenuBoardVisionExtractor(): FakeMenuBoardVisionExtractor = FakeMenuBoardVisionExtractor()
}
