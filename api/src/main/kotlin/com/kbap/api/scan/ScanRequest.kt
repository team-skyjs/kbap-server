package com.kbap.api.scan

import com.fasterxml.jackson.annotation.JsonIgnore
import com.kbap.common.port.llm.OcrItem
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

@Schema(description = "메뉴판 사진 스캔 요청 — 검증된 이미지 경로 + 클라이언트 자체 OCR 항목(박스 매칭용).")
data class ScanRequest(
    @field:NotBlank(message = "imagePath 는 필수입니다")
    @field:Size(max = 512, message = "imagePath 는 최대 512자입니다")
    @field:Pattern(regexp = "^(?!https?://).*", message = "imagePath 는 전체 URL 이 아닌 오브젝트 경로여야 합니다")
    @field:Schema(
        description = "스캔할 메뉴판 사진의 오브젝트 경로(CDN 도메인 제외). 업로드 완료 신고가 검증한 본인 소유 이미지여야 한다.",
        example = "scan/123/20260715-abc123.jpg",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val imagePath: String?,

    @field:Size(max = 64, message = "requestId 는 최대 64자입니다")
    @field:Schema(
        description = "스캔 요청 고유 식별자(옵션, UUID 권장). 재시도 중복 전달 시 무료 슬롯 이중 예약을 막는 멱등 키 — " +
            "같은 requestId 가 처리 중이면 409(SCAN-005). 미전송 시 서버가 생성한다(멱등 미보장).",
        example = "3f1c9a2e-8d4b-4f6a-9c1d-2b7e5a0f4c88",
    )
    val requestId: String? = null,

    @field:NotEmpty(message = "items 는 최소 1개 이상이어야 합니다")
    @field:Size(max = MAX_ITEMS, message = "items 는 최대 ${MAX_ITEMS}개입니다")
    @field:Schema(
        description = "클라이언트가 같은 사진을 OCR 한 항목 목록(1~100개). 각 항목의 idx 로 응답 결과와 매칭해 UI 박스를 그린다.",
    )
    val items: List<@Valid ScanItemRequest> = emptyList(),
) {
    fun toOcrItems(): List<OcrItem> = items.map { OcrItem(idx = it.idx!!, rawMenuName = it.rawMenuName!!) }

    @get:JsonIgnore
    @get:AssertTrue(message = "idx 는 요청 안에서 중복될 수 없습니다")
    val idxUnique: Boolean
        get() {
            val indexes = items.mapNotNull { it.idx }
            return indexes.size == indexes.toSet().size
        }

    companion object {
        const val MAX_ITEMS = 100
    }
}

@Schema(description = "클라이언트 OCR 로 인식한 개별 메뉴 항목")
data class ScanItemRequest(
    @field:NotNull(message = "idx 는 필수입니다")
    @field:Schema(
        description = "클라이언트가 OCR 항목마다 부여하는 식별자. 응답 results[].idx 와 매칭되어 그 메뉴 위에 박스를 그린다. 한 요청 안에서 유일해야 한다.",
        example = "0",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val idx: Int?,

    @field:NotBlank(message = "rawMenuName 은 blank 일 수 없습니다")
    @field:Schema(
        description = "클라이언트 OCR 이 인식한 메뉴의 원문 텍스트. 서버가 사진 추출 결과를 이 항목에 매칭하는 힌트로 쓴다.",
        example = "김치찌개",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val rawMenuName: String?,
)
