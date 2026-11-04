package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 가공식품 조회", description = "관리자 전용 — 가공식품 한 건을 숨김 상태까지 포함해 본다(S-963)")
@SecurityRequirement(name = "bearerAuth")
interface AdminProcessedFoodDetailApi {
    @Operation(
        summary = "가공식품 단건 조회(숨긴 상품 포함)",
        description = """
            앱 상세와 달리 DELETED(숨긴) 상품도 준다 — 숨김·복원(PATCH .../status) 전후 확인과 kcal 현재값 확인용.
            - **ADMIN 역할 JWT 전용**, `X-Target-Environment` 가 서버 환경과 같아야 한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회"),
            ApiResponse(responseCode = "404", description = "가공식품 없음(PFOOD-001)"),
            ApiResponse(responseCode = "409", description = "대상 환경 불일치(PFOOD-012)"),
        ],
    )
    fun getProduct(
        @Parameter(description = "가공식품 id") id: Long,
        @Parameter(description = "대상 환경(dev·prod) — 서버 환경과 다르면 409") target: String,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodDetailResponse>>
}
