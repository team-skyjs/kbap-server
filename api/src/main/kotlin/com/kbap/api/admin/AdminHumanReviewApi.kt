package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 검수 기록", description = "관리자 전용 — 사람 검수 완료 기록의 관리자별 건수와 최신순 목록")
interface AdminHumanReviewApi {
    @Operation(
        summary = "검수 기록 목록·관리자별 건수",
        description = """
            사람 검수 완료가 기록된 음식을 검수 시각 최신순으로 내려준다(커서 기반, 20건). `summary` 는 관리자별 건수이며
            `adminId` 필터와 무관하게 전체 기준이다.

            - `adminId` 를 주면 그 관리자가 검수한 음식만.
            - `cursor` 는 직전 응답의 `nextCursor`(마지막 항목의 foodId)를 그대로 되돌려준다. 검수 기록이 없는 foodId 를 커서로 주면 400(FOOD-002).
            - **소프트삭제된 음식의 기록도 유지된다** — 목록(`deleted: true`)과 건수에 그대로 남는다. 관리자가 한 일은 음식이 나중에 지워져도 사라지지 않는다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "400", description = "커서 불량(FOOD-002)"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiErrors(ErrorCode.INVALID_CURSOR)
    fun getHumanReviews(
        @Parameter(description = "검수한 관리자 계정 id 로 필터", example = "2") adminId: Long?,
        @Parameter(description = "직전 응답의 nextCursor", example = "42") cursor: Long?,
    ): ResponseEntity<BaseResponse<AdminHumanReviewListResponse>>
}
