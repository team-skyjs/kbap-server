package com.meogo.api.scan.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

/**
 * POST /menu-scans 요청 본문. Bean Validation 으로 1차 검증하고,
 * itemId 중복(컬렉션 유일성)은 컨트롤러에서 수동 검사한다(R8).
 */
data class SubmitMenuScanRequest(
    @field:NotEmpty(message = "items 는 최소 1개여야 합니다")
    @field:Size(max = 100, message = "items 는 최대 100개입니다")
    @field:Valid
    val items: List<MenuScanItemRequest> = emptyList(),
)

data class MenuScanItemRequest(
    @field:NotNull(message = "itemId 는 필수입니다")
    val itemId: Int?,

    @field:NotBlank(message = "rawMenuName 은 blank 일 수 없습니다")
    val rawMenuName: String?,

    @field:NotNull(message = "boundingBox 는 필수입니다")
    @field:Valid
    val boundingBox: BoundingBoxRequest?,
)

/**
 * 정규화 비율 좌표. 단일 필드 제약은 어노테이션으로, 교차 제약(x+width≤1, y+height≤1)은
 * [withinBounds] @AssertTrue 로 검증한다.
 */
data class BoundingBoxRequest(
    @field:NotNull(message = "boundingBox.x 는 필수입니다")
    @field:PositiveOrZero(message = "boundingBox.x 는 0 이상이어야 합니다")
    @field:DecimalMax(value = "1.0", message = "boundingBox.x 는 1 이하여야 합니다")
    val x: Double?,

    @field:NotNull(message = "boundingBox.y 는 필수입니다")
    @field:PositiveOrZero(message = "boundingBox.y 는 0 이상이어야 합니다")
    @field:DecimalMax(value = "1.0", message = "boundingBox.y 는 1 이하여야 합니다")
    val y: Double?,

    @field:NotNull(message = "boundingBox.width 는 필수입니다")
    @field:Positive(message = "boundingBox.width 는 0 보다 커야 합니다")
    @field:DecimalMax(value = "1.0", message = "boundingBox.width 는 1 이하여야 합니다")
    val width: Double?,

    @field:NotNull(message = "boundingBox.height 는 필수입니다")
    @field:Positive(message = "boundingBox.height 는 0 보다 커야 합니다")
    @field:DecimalMax(value = "1.0", message = "boundingBox.height 는 1 이하여야 합니다")
    val height: Double?,
) {
    @get:JsonIgnore
    @get:AssertTrue(message = "boundingBox 의 x+width·y+height 는 각각 1 이하여야 합니다")
    val withinBounds: Boolean
        get() {
            val px = x ?: return true
            val py = y ?: return true
            val pw = width ?: return true
            val ph = height ?: return true
            return px + pw <= 1.0 && py + ph <= 1.0
        }
}
