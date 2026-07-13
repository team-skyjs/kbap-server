package com.kbap.app.api.scan

import com.fasterxml.jackson.annotation.JsonIgnore
import com.kbap.domain.scan.dto.ScanItemInput
import com.kbap.domain.scan.dto.ScanInput
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

@Schema(description = "메뉴 스캔 제출 요청 — 스캔으로 인식한 메뉴 항목 배열을 담는다.")
data class ScanRequest(
    @field:NotEmpty(message = "items 는 최소 1개여야 합니다")
    @field:Size(max = MAX_ITEMS, message = "items 는 최대 ${MAX_ITEMS}개입니다")
    @field:Schema(
        description = "스캔한 메뉴 항목 목록 (1~100개). 각 항목의 idx 로 응답 결과와 1:1 매칭한다.",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val items: List<@Valid ScanItemRequest> = emptyList(),
) {
    fun toInput(memberId: Long): ScanInput =
        ScanInput(items = items.map { it.toInput() }, memberId = memberId)

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

@Schema(description = "스캔한 개별 메뉴 항목")
data class ScanItemRequest(
    @field:NotNull(message = "idx 는 필수입니다")
    @field:Schema(
        description = "클라이언트가 스캔한 메뉴 각각에 부여하는 식별자. 응답 results[].idx 와 1:1 매칭되어, 클라이언트가 자기 화면의 메뉴와 판정 결과를 연결하는 용도다. 배열 인덱스를 그대로 써도 되지만 서버는 순서로 해석하지 않는다. 한 요청 안에서만 유일하면 된다.",
        example = "0",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val idx: Int?,

    @field:NotBlank(message = "rawMenuName 은 blank 일 수 없습니다")
    @field:Schema(
        description = "스캔으로 인식한 메뉴의 원문 이름",
        example = "된장찌개",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val rawMenuName: String?,
) {
    fun toInput(): ScanItemInput =
        ScanItemInput(idx = idx!!, rawMenuName = rawMenuName!!)
}
