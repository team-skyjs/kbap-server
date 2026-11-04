package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 가공식품 조리법", description = "관리자 전용 — 라면 조리법 입력(S-961, KB-994, D-34)")
@SecurityRequirement(name = "bearerAuth")
interface AdminProcessedFoodRecipeApi {
    @Operation(
        summary = "가공식품 조리법 전체 교체",
        description = """
            포장 표시를 보고 넣은 조리법으로 통째로 바꾼다. `recipe: null` 이면 지운다(앱 상세에서 조리법 섹션이 사라진다).
            숫자(물·와트별 시간·기다림·남길 물)는 구조화하고, 단계·주의 문구는 한국어 원문만 넣는다 — 상세 lang 번역은 서버가 한다.
            검증: 방법 1~4개, 단계 1~12개, 와트 100~2000, 초 0~3600, ml 0~2000. 위반은 400 COMMON-002. HACCP 미러는 이 값을 덮지 않는다.
            - **ADMIN 역할 JWT 전용**.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "저장(또는 삭제)"),
            ApiResponse(responseCode = "400", description = "범위 위반·필수값 누락(COMMON-002)"),
            ApiResponse(responseCode = "404", description = "가공식품 없음(PFOOD-001)"),
        ],
    )
    fun putRecipe(
        @Parameter(description = "가공식품 id") id: Long,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "조리법(null 이면 삭제)")
        request: AdminProcessedFoodRecipePutRequest,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodRecipePutResponse>>
}
