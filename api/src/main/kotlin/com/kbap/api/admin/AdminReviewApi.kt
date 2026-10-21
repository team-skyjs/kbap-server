package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 리뷰", description = "관리자 전용 — 전체 회원 리뷰 목록 API")
@SecurityRequirement(name = "bearerAuth")
interface AdminReviewApi {
    @Operation(
        summary = "전체 리뷰 목록",
        description = """
            전 회원의 리뷰를 id 내림차순 20개씩 페이지 조회한다 — 음식·가공식품 리뷰 모두, 삭제된 리뷰 제외.
            봉투는 회원별 리뷰 페이지와 같다(items·page·totalPages·totalCount·hasPrev·hasNext).
            항목에 작성자 id·닉네임·봇 여부와 대상 타입(FOOD|PROCESSED_FOOD)·대상 id·대상 이름이 온다.
            `isBot` 은 작성자 기준(생략 = 전체).
            - **ADMIN 역할 JWT 전용** — USER 토큰은 403(AUTH-008).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 없으면 빈 items 와 totalCount=0"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun getReviewPage(
        @Parameter(description = "1부터 시작하는 페이지 번호(1 미만이면 1로 보정)", example = "1")
        page: Int,
        @Parameter(description = "작성자가 봇이면 true, 사람이면 false, 생략하면 전체", example = "false")
        isBot: Boolean?,
    ): ResponseEntity<BaseResponse<AdminReviewPageResponse>>
}
