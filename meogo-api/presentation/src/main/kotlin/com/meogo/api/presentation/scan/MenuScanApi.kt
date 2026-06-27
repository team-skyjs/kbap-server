package com.meogo.api.presentation.scan

import com.meogo.api.presentation.common.BaseResponse
import com.meogo.api.presentation.scan.dto.SubmitMenuScanRequest
import com.meogo.api.presentation.scan.dto.SubmitMenuScanResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "메뉴 스캔", description = "메뉴판 스캔 제출·판정 API")
interface MenuScanApi {
    @Operation(
        summary = "메뉴 스캔 제출",
        description = "스캔한 메뉴 항목(이름·boundingBox)들을 제출하면, 각 항목에 4단계 위험도(SAFE/CAUTION/DANGER/UNKNOWN) 판정을 매겨 돌려준다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "판정 성공 — scanId 와 항목별 판정 결과 반환"),
            ApiResponse(responseCode = "400", description = "요청 검증 실패 — 필수값 누락 또는 itemId 중복"),
        ],
    )
    fun submit(request: SubmitMenuScanRequest): ResponseEntity<BaseResponse<SubmitMenuScanResponse>>
}
