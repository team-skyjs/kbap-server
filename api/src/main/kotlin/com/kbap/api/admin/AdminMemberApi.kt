package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 멤버", description = "관리자 전용 — 어드민 SPA 의 멤버 목록/검색·상세·활동 조회 API")
@SecurityRequirement(name = "bearerAuth")
interface AdminMemberApi {
    @Operation(
        summary = "멤버 목록/검색 조회",
        description = """
            전체 멤버를 id 내림차순으로 페이지 조회한다(탈퇴 포함 — `memberStatus` 로 구분).

            - `q` 를 주면 닉네임·이메일 부분 일치로 검색하고, 숫자면 멤버 id 일치도 함께 매칭한다.
            - 페이지 크기는 서버 고정(20)이며, 응답에 전체 건수(`totalCount`)를 포함한다.
            - **ADMIN 역할 JWT 전용** — USER 토큰은 403(AUTH-008) 으로 거절된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 대상이 없으면 빈 items 와 totalCount=0"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun searchMemberPage(
        @Parameter(description = "1부터 시작하는 페이지 번호(1 미만이면 1로 보정)", example = "1")
        page: Int,
        @Parameter(description = "닉네임·이메일 부분 일치 검색어(숫자면 멤버 id 일치도 매칭)", example = "김밥")
        q: String?,
    ): ResponseEntity<BaseResponse<AdminMemberListResponse>>

    @Operation(
        summary = "멤버 상세 조회",
        description = """
            멤버 1건의 프로필·회피 설정 요약·활동 수(스캔·리뷰·주문)·제재 이력 자리를 반환한다.

            - `sanctions` 는 정지 모델 도입 전이라 항상 빈 배열이다 — 구조는 후속 확정.
            - 없는 id 는 400(MEMBER-003).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "400", description = "멤버 없음(MEMBER-003)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun getMemberDetail(
        @Parameter(description = "조회할 멤버 id", example = "1")
        id: Long,
    ): ResponseEntity<BaseResponse<AdminMemberDetailResponse>>

    @Operation(
        summary = "멤버 리뷰 목록 조회",
        description = "해당 멤버가 작성한 리뷰를 id 내림차순으로 페이지 조회한다(음식 이름 포함, 페이지 크기 20). 없는 멤버는 400(MEMBER-003).",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "400", description = "멤버 없음(MEMBER-003)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun getMemberReviewPage(
        @Parameter(description = "멤버 id", example = "1")
        id: Long,
        @Parameter(description = "1부터 시작하는 페이지 번호", example = "1")
        page: Int,
    ): ResponseEntity<BaseResponse<AdminMemberReviewPageResponse>>

    @Operation(
        summary = "멤버 스캔 이력 조회",
        description = """
            해당 멤버의 메뉴판 스캔 이력을 id 내림차순으로 페이지 조회한다(페이지 크기 20).
            음식 미매칭 스캔은 `foodId`·`foodName` 이 null 이다. 없는 멤버는 400(MEMBER-003).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "400", description = "멤버 없음(MEMBER-003)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun getMemberScanPage(
        @Parameter(description = "멤버 id", example = "1")
        id: Long,
        @Parameter(description = "1부터 시작하는 페이지 번호", example = "1")
        page: Int,
    ): ResponseEntity<BaseResponse<AdminMemberScanPageResponse>>

    @Operation(
        summary = "멤버 주문 목록 조회",
        description = "해당 멤버의 주문을 id 내림차순으로 페이지 조회한다(메뉴판 이미지 URL·주소 포함, 페이지 크기 20). 없는 멤버는 400(MEMBER-003).",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "400", description = "멤버 없음(MEMBER-003)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun getMemberOrderPage(
        @Parameter(description = "멤버 id", example = "1")
        id: Long,
        @Parameter(description = "1부터 시작하는 페이지 번호", example = "1")
        page: Int,
    ): ResponseEntity<BaseResponse<AdminMemberOrderPageResponse>>
}
