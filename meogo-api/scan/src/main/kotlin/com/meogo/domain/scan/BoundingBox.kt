package com.meogo.domain.scan

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
