package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 메뉴 음식 가공식품 연결", description = "관리자 전용 — 메뉴 음식(콜라·사이다 등)을 가공식품 대표 상품에 연결(S-968)")
@SecurityRequirement(name = "bearerAuth")
interface AdminFoodProcessedLinkApi {
    @Operation(
        summary = "가공식품 연결 지정",
        description = """
            음식 하나에 가공식품 하나를 연결한다(이미 있으면 바꾼다). 누끼 이미지가 승인된 상품만 연결된다.
            후보는 `GET /api/admin/processed-foods/cutout-images?status=APPROVED&q=` 로 찾는다.
            앱의 스캔 결과·음식 상세가 `linkedProcessedFood` 로 이 상품을 싣는다. 위험도·재료 판정은 음식 자체 값을 그대로 쓴다.
            - **ADMIN 역할 JWT 전용**. `X-Target-Environment` 가 서버 환경과 다르면 409(FOOD-026).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "연결"),
            ApiResponse(responseCode = "400", description = "음식 없음(FOOD-001)·processedFoodId 누락(COMMON-002)"),
            ApiResponse(responseCode = "404", description = "가공식품 없음(PFOOD-001)"),
            ApiResponse(responseCode = "409", description = "누끼 미승인 상품(PFOOD-017)·환경 불일치(FOOD-026)"),
        ],
    )
    fun link(
        @Parameter(description = "음식 id") id: Long,
        @Parameter(description = "대상 환경(dev·prod)") target: String,
        adminAccountId: Long,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "연결할 가공식품")
        request: AdminFoodProcessedLinkRequest,
    ): ResponseEntity<BaseResponse<AdminFoodProcessedLinkResponse>>

    @Operation(
        summary = "가공식품 연결 해제",
        description = """
            연결을 지운다(멱등 — 없으면 unlinked=false). 앱 응답에서 `linkedProcessedFood` 가 빠진다.
            - **ADMIN 역할 JWT 전용**. `X-Target-Environment` 가 서버 환경과 다르면 409(FOOD-026).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "해제(또는 원래 없음)"),
            ApiResponse(responseCode = "409", description = "환경 불일치(FOOD-026)"),
        ],
    )
    fun unlink(
        @Parameter(description = "음식 id") id: Long,
        @Parameter(description = "대상 환경(dev·prod)") target: String,
    ): ResponseEntity<BaseResponse<AdminFoodProcessedUnlinkResponse>>
}
