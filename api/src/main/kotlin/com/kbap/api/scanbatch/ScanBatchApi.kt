package com.kbap.api.scanbatch

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "여러 장 스캔", description = "메뉴판 사진 1~10장을 한 묶음으로 스캔한다(KB-744). X-API-Version 3.3 이상, 회원 전용. 한 장이라도 결과가 저장되면 묶음당 1회 차감, 전부 실패·결과 전 취소는 0회, 같은 묶음 재시도는 추가 0회. 단일 스캔과 무료 횟수·해제 혜택을 공유한다.")
@SecurityRequirement(name = "bearerAuth")
interface ScanBatchApi {
    @Operation(
        summary = "스캔 묶음 생성",
        description = """
            사진 1~10장의 고정 목록으로 묶음을 만들고 공통 잔량 1회를 실행 창(15분) 동안 예약한다. 사진은 PENDING 으로 시작한다.
            clientRequestId 가 멱등 키다 — 같은 내용 재전송은 같은 묶음, 다른 내용은 409 SCAN-005.
            사진 경로는 본인이 MENU_SCAN 목적으로 업로드 완료한 것만(SCAN-001). 11장 이상 SCAN-009, 잔량 소진 SCAN-004.
            기능 플래그가 꺼져 있으면 404 SCAN-010.
        """,
    )
    @ApiErrors(
        ErrorCode.SCAN_BATCH_TOO_MANY_PHOTOS,
        ErrorCode.SCAN_IMAGE_NOT_VERIFIED,
        ErrorCode.SCAN_LIMIT_EXCEEDED,
        ErrorCode.DUPLICATE_SCAN_REQUEST,
        ErrorCode.SCAN_BATCH_NOT_FOUND,
        ErrorCode.INVALID_CURRENCY_CODE,
    )
    fun create(
        memberId: Long,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "스캔 묶음 생성 요청") request: ScanBatchCreateRequest,
    ): ResponseEntity<BaseResponse<ScanBatchResponse>>

    @Operation(
        summary = "스캔 묶음 조회",
        description = "사진별 상태·시도 수·실패 사유·재시도 가능 여부와 차감 여부를 저장본에서 읽는다(유료 호출·차감 없음). 응답 유실은 이 조회로 복구한다. 결과 보관 7일이 지나면 404 SCAN-010.",
    )
    @ApiErrors(ErrorCode.SCAN_BATCH_NOT_FOUND)
    fun get(
        memberId: Long,
        @Parameter(description = "묶음 id", example = "0b6b5c6e-3f0a-4c8e-9a51-1f0f3b7d2a10") batchId: String,
    ): ResponseEntity<BaseResponse<ScanBatchResponse>>

    @Operation(
        summary = "스캔 묶음 취소",
        description = "멱등. 결과가 저장되기 전이면 0회(예약 해제), 이미 결과가 저장됐으면 1회 차감은 유지된다. 남은 사진은 CANCELLED 가 되고 늦게 끝난 결과는 저장하지 않는다.",
    )
    @ApiErrors(ErrorCode.SCAN_BATCH_NOT_FOUND)
    fun cancel(
        memberId: Long,
        @Parameter(description = "묶음 id", example = "0b6b5c6e-3f0a-4c8e-9a51-1f0f3b7d2a10") batchId: String,
    ): ResponseEntity<BaseResponse<ScanBatchResponse>>
}
