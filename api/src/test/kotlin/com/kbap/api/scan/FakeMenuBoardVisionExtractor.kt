package com.kbap.api.scan

import com.kbap.common.port.llm.ExtractedMenu
import com.kbap.common.port.llm.MenuBoardVisionExtractor
import com.kbap.common.port.llm.OcrItem
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// 테스트용 페이크 비전 인식 — 실 OpenAI 없이 path 별 추출 결과를 주입하거나 인식 실패를 흉내낸다.
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

// 전 api 통합 테스트가 공유하는 페이크 — ScanService 가 MenuBoardVisionExtractor 빈을 요구하므로
// 항상 스캔되는 @Configuration 으로 제공한다(실 vision 빈은 kbap.llm.vision.enabled 로 꺼져 있어 충돌 없음).
@Configuration
class FakeVisionConfig {
    @Bean
    fun fakeMenuBoardVisionExtractor(): FakeMenuBoardVisionExtractor = FakeMenuBoardVisionExtractor()
}
