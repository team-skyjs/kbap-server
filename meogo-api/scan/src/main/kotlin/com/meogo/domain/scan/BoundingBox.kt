package com.meogo.domain.scan

/**
 * 정규화 비율 좌표(클라이언트 OCR 기준 이미지 대비). 좌상단 (0,0)·우하단 (1,1).
 * 판정에는 쓰이지 않고 UI 오버레이 복원·재현용으로 저장한다.
 * 불변식(생성 시 검증): x≥0 ∧ y≥0 ∧ width>0 ∧ height>0 ∧ x+width≤1 ∧ y+height≤1.
 */
data class BoundingBox(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    init {
        require(x >= 0.0) { "boundingBox.x 는 0 이상이어야 합니다 (x=$x)" }
        require(y >= 0.0) { "boundingBox.y 는 0 이상이어야 합니다 (y=$y)" }
        require(width > 0.0) { "boundingBox.width 는 0 보다 커야 합니다 (width=$width)" }
        require(height > 0.0) { "boundingBox.height 는 0 보다 커야 합니다 (height=$height)" }
        require(x + width <= 1.0) { "boundingBox 의 x+width 는 1 이하여야 합니다 (x=$x, width=$width)" }
        require(y + height <= 1.0) { "boundingBox 의 y+height 는 1 이하여야 합니다 (y=$y, height=$height)" }
    }
}
