package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 벡터 아웃박스", description = "관리자 전용 — 음식 벡터 동기화 아웃박스 조회·enqueue·재시도 API")
@SecurityRequirement(name = "bearerAuth")
interface AdminVectorOutboxApi {
    @Operation(
        summary = "벡터 아웃박스 목록 조회",
        description = """
            벡터 동기화 아웃박스를 id 내림차순으로 페이지 조회한다. 어드민 SPA 벡터 동기화 화면 전용이다.

            - `status` 를 주면 해당 상태(PENDING·COMPLETE·FAILED)만 필터링한다.
            - 페이지 크기는 서버 고정(50)이며, 응답에 전체 건수(`totalCount`)와 전체 페이지 수를 포함한다.
            - `displayName` 은 대상 음식이 소프트삭제됐으면 null 이다.
            - **ADMIN 역할 JWT 전용** — USER 토큰은 403(AUTH-008) 으로 거절된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 대상이 없으면 빈 items 와 totalCount=0"),
            ApiResponse(responseCode = "400", description = "status 가 유효한 아웃박스 상태가 아님(COMMON-002)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun getVectorOutboxPage(
        @Parameter(description = "1부터 시작하는 페이지 번호(1 미만이면 1로 보정)", example = "1")
        page: Int,
        @Parameter(description = "아웃박스 상태 필터", example = "FAILED")
        status: FoodVectorOutboxStatus?,
    ): ResponseEntity<BaseResponse<AdminVectorOutboxPageResponse>>

    @Operation(
        summary = "벡터 동기화 일괄 enqueue",
        description = """
            READY 인데 UPSERT 아웃박스가 없는 음식을 최대 500건 enqueue 하고 생성 건수를 반환한다.

            - 멱등: 이미 아웃박스가 있는 음식은 대상에서 빠지므로 재호출 시 `enqueued=0` 으로 성공한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "요청 성공 — 대상이 없으면 enqueued=0"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun enqueueVectorOutboxes(): ResponseEntity<BaseResponse<AdminVectorOutboxEnqueueResponse>>

    @Operation(
        summary = "벡터 아웃박스 재시도",
        description = """
            FAILED 아웃박스 1건을 PENDING 으로 되돌려 재시도한다(시도 횟수 초기화).

            - 멱등: 이미 PENDING·COMPLETE 면 변경 없이 `retried=false` 와 현재 상태를 반환한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "요청 성공 — retried 와 현재 상태 반환"),
            ApiResponse(responseCode = "400", description = "아웃박스 없음(FOOD-007)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun retryVectorOutbox(
        @Parameter(description = "재시도할 아웃박스 id", example = "1")
        id: Long,
    ): ResponseEntity<BaseResponse<AdminVectorOutboxRetryResponse>>
}
