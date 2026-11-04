package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 가공식품 칼로리", description = "관리자 전용 — 포장 전체 칼로리(kcal) 입력(S-957)")
@SecurityRequirement(name = "bearerAuth")
interface AdminProcessedFoodKcalApi {
    @Operation(
        summary = "가공식품 칼로리 입력",
        description = """
            HACCP·C002·수동 등록 상품 모두 포장 전체 kcal 을 넣는다(null 이면 지운다). HACCP 미러는 이 값을 덮지 않는다.
            앱 상세 `kcal` 은 이 값이되, 같은 묶음 안 상품의 (용량, kcal) 이 둘 이상으로 갈리면 null 이다.
            - **ADMIN 역할 JWT 전용**.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "저장"),
            ApiResponse(responseCode = "400", description = "kcalTotal 음수(COMMON-002)"),
            ApiResponse(responseCode = "404", description = "가공식품 없음(PFOOD-001)"),
        ],
    )
    fun updateKcal(
        @Parameter(description = "가공식품 id") id: Long,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "칼로리")
        request: AdminProcessedFoodKcalRequest,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodKcalResponse>>
}
