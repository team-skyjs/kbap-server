package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 가공식품 숨김", description = "관리자 전용 — 판매 종료·리뉴얼 전 상품 숨김·복원(S-958)")
@SecurityRequirement(name = "bearerAuth")
interface AdminProcessedFoodStatusApi {
    @Operation(
        summary = "가공식품 숨김·복원",
        description = """
            status 를 DELETED 로 바꾸면 상세(404 PFOOD-001)·검색·목록·이름 스캔 후보·묶음에서 빠지고, ACTIVE 로 되돌리면 다시 보인다.
            같은 상태로 다시 보내도 결과가 같다(changed=false). HACCP 미러·C002 적재는 숨긴 상품을 되살리거나 새로 넣지 않고 건너뛴다.
            변경은 서버 로그 한 줄(id·이전→이후 상태·관리자 id)로 남긴다.
            - **ADMIN 역할 JWT 전용**, `X-Target-Environment` 가 서버 환경과 같아야 한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "변경(또는 이미 그 상태)"),
            ApiResponse(responseCode = "400", description = "status 누락·잘못된 값(COMMON-002)"),
            ApiResponse(responseCode = "404", description = "가공식품 없음(PFOOD-001)"),
            ApiResponse(responseCode = "409", description = "대상 환경 불일치(PFOOD-012)"),
        ],
    )
    fun changeStatus(
        @Parameter(description = "가공식품 id") id: Long,
        @Parameter(description = "대상 환경(dev·prod) — 서버 환경과 다르면 409") target: String,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "바꿀 상태")
        request: AdminProcessedFoodStatusRequest,
        adminId: Long,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodStatusResponse>>
}
