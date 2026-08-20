package com.kbap.common.port.llm

// 입력은 오브젝트 path 와 클라이언트 OCR 힌트 — 전체 URL(CDN 도메인) 조합은 구현이 서버 설정으로 수행한다.
interface MenuBoardVisionExtractor {
    fun extract(imagePath: String, ocrItems: List<OcrItem>): List<ExtractedMenu>
}

class MenuBoardVisionUnavailableException(cause: Throwable) : RuntimeException(cause)

// 클라이언트 자체 OCR 결과 1건 — idx 는 클라이언트 UI 박스와 결과를 잇는 키다.
data class OcrItem(
    val idx: Int,
    val rawMenuName: String,
)

data class ExtractedMenu(
    val name: String,
    val koreanName: String,
    val priceKrw: Int?,
    // 이 추출 메뉴가 대응하는 클라이언트 OCR 항목의 idx. 대응 OCR 이 없으면 null(박스 없음).
    val matchedIdx: Int?,
) {
    init {
        require(name.isNotBlank()) { "name 은 blank 일 수 없습니다" }
        require(koreanName.isNotBlank()) { "koreanName 은 blank 일 수 없습니다" }
        require(priceKrw == null || priceKrw >= 0) { "priceKrw 는 음수일 수 없습니다" }
    }
}
