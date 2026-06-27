package com.meogo.domain.scan

/**
 * 스캔된 메뉴 항목(MenuScan 애그리거트 내부 엔티티).
 *
 * @param itemId        클라이언트 제공 식별자. 스캔 내에서 유일.
 * @param rawMenuName   OCR 원문 메뉴명(blank 불가).
 * @param boundingBox   정규화 비율 좌표(UI 오버레이 복원용).
 * @param receivedOrder 수신 배열 순서(0-based) — 판정 순서·재현용.
 * @param assessment    mock 판정 스냅샷.
 */
data class ScannedMenuItem(
    val itemId: Int,
    val rawMenuName: String,
    val boundingBox: BoundingBox,
    val receivedOrder: Int,
    val assessment: MenuItemAssessment,
) {
    init {
        require(rawMenuName.isNotBlank()) { "rawMenuName 은 blank 일 수 없습니다" }
    }
}
