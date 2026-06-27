package com.meogo.api.presentation.scan

import com.fasterxml.jackson.annotation.JsonIgnore
import com.meogo.api.application.scan.BoundingBoxInput
import com.meogo.api.application.scan.MenuScanItemInput
import com.meogo.api.application.scan.SubmitMenuScanInput
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

@Schema(description = "메뉴 스캔 제출 요청 — 스캔으로 인식한 메뉴 항목 배열을 담는다.")
data class SubmitMenuScanRequest(
    @field:NotEmpty(message = "items 는 최소 1개여야 합니다")
    @field:Size(max = 100, message = "items 는 최대 100개입니다")
    @field:Schema(
        description = "스캔한 메뉴 항목 목록 (1~100개). 각 항목의 itemId 로 응답 결과와 1:1 매칭한다.",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val items: List<@Valid MenuScanItemRequest> = emptyList(),
) {
    fun toInput(): SubmitMenuScanInput =
        SubmitMenuScanInput(items = items.map { it.toInput() })
}

@Schema(description = "스캔한 개별 메뉴 항목")
data class MenuScanItemRequest(
    @field:NotNull(message = "itemId 는 필수입니다")
    @field:Schema(
        description = "클라이언트가 스캔한 메뉴 각각에 부여하는 식별자. 응답 results[].itemId 와 1:1 매칭되어, 클라이언트가 자기 화면의 메뉴와 판정 결과를 연결하는 용도다. 순서를 뜻하지 않으며 한 요청 안에서만 유일하면 된다 — 메뉴명이 같아도 itemId 가 다르면 별개 항목으로 처리한다.",
        example = "0",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val itemId: Int?,

    @field:NotBlank(message = "rawMenuName 은 blank 일 수 없습니다")
    @field:Schema(
        description = "스캔으로 인식한 메뉴의 원문 이름",
        example = "된장찌개",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val rawMenuName: String?,

    @field:NotNull(message = "boundingBox 는 필수입니다")
    @field:Valid
    @field:Schema(
        description = "메뉴판 이미지에서 해당 메뉴가 차지하는 영역의 정규화 좌표 박스(0.0~1.0)",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val boundingBox: BoundingBoxRequest?,
) {
    fun toInput(): MenuScanItemInput =
        MenuScanItemInput(
            itemId = itemId!!,
            rawMenuName = rawMenuName!!,
            boundingBox = boundingBox!!.toInput(),
        )
}

@Schema(description = "정규화 좌표 바운딩 박스 (각 값 0.0~1.0, x+width·y+height 는 각각 1.0 이하)")
data class BoundingBoxRequest(
    @field:NotNull(message = "boundingBox.x 는 필수입니다")
    @field:PositiveOrZero(message = "boundingBox.x 는 0 이상이어야 합니다")
    @field:DecimalMax(value = "1.0", message = "boundingBox.x 는 1 이하여야 합니다")
    @field:Schema(description = "박스 좌상단 x (정규화 0.0~1.0)", example = "0.1", requiredMode = Schema.RequiredMode.REQUIRED)
    val x: Double?,

    @field:NotNull(message = "boundingBox.y 는 필수입니다")
    @field:PositiveOrZero(message = "boundingBox.y 는 0 이상이어야 합니다")
    @field:DecimalMax(value = "1.0", message = "boundingBox.y 는 1 이하여야 합니다")
    @field:Schema(description = "박스 좌상단 y (정규화 0.0~1.0)", example = "0.1", requiredMode = Schema.RequiredMode.REQUIRED)
    val y: Double?,

    @field:NotNull(message = "boundingBox.width 는 필수입니다")
    @field:Positive(message = "boundingBox.width 는 0 보다 커야 합니다")
    @field:DecimalMax(value = "1.0", message = "boundingBox.width 는 1 이하여야 합니다")
    @field:Schema(description = "박스 너비 (정규화, 0 초과, x+width ≤ 1.0)", example = "0.5", requiredMode = Schema.RequiredMode.REQUIRED)
    val width: Double?,

    @field:NotNull(message = "boundingBox.height 는 필수입니다")
    @field:Positive(message = "boundingBox.height 는 0 보다 커야 합니다")
    @field:DecimalMax(value = "1.0", message = "boundingBox.height 는 1 이하여야 합니다")
    @field:Schema(description = "박스 높이 (정규화, 0 초과, y+height ≤ 1.0)", example = "0.08", requiredMode = Schema.RequiredMode.REQUIRED)
    val height: Double?,
) {
    fun toInput(): BoundingBoxInput =
        BoundingBoxInput(x = x!!, y = y!!, width = width!!, height = height!!)

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
