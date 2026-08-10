package com.kbap.api.scan

import com.kbap.common.port.llm.ExtractedMenu
import com.kbap.common.port.llm.MenuBoardVisionExtractor
import com.kbap.common.port.llm.OcrItem
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class FakeMenuBoardVisionExtractor : MenuBoardVisionExtractor {
    private val byPath = mutableMapOf<String, List<ExtractedMenu>>()
    private val failPaths = mutableSetOf<String>()
    val receivedOcrItems: MutableMap<String, List<OcrItem>> = mutableMapOf()

    fun program(path: String, menus: List<ExtractedMenu>) {
        byPath[path] = menus
    }

    fun failOn(path: String) {
        failPaths.add(path)
    }

    override fun extract(imagePath: String, ocrItems: List<OcrItem>): List<ExtractedMenu> {
        receivedOcrItems[imagePath] = ocrItems
        if (imagePath in failPaths) throw RuntimeException("vision 인식 실패(테스트)")
        return byPath[imagePath] ?: emptyList()
    }
}

@Configuration
class FakeVisionConfig {
    @Bean
    fun fakeMenuBoardVisionExtractor(): FakeMenuBoardVisionExtractor = FakeMenuBoardVisionExtractor()
}
