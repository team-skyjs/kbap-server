package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@Tag(name = "관리자 음식 승인", description = "관리자 전용 — 이미지까지 채워진 음식을 승인해 조회 가능(READY)으로 올리거나 반려하는 API")
@SecurityRequirement(name = "bearerAuth")
interface AdminFoodContentReviewApi {
    @Operation(
        summary = "승인 대기 음식 조회",
        description = """
            콘텐츠와 이미지가 모두 채워져 관리자 승인을 기다리는 **PENDING_REVIEW** 음식을 판단에 필요한 필드와 함께 반환한다.

            - `avoidanceSubstances` 는 미조사(null)여도 빈 배열로 내려간다.
            - `imageUrl` 은 공개 이미지 URL 이며, 이미지가 없으면 null 이다.
            - `contentReviewAttempts` 는 지금까지의 반려 횟수다(관리자 판단 참고용).
            - **ADMIN 역할 JWT 전용** — USER 토큰은 403(AUTH-008) 으로 거절된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공 — 대상이 없으면 빈 배열"),
            ApiResponse(responseCode = "400", description = "limit 이 1..200 범위 밖(COMMON-002)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun getContentReviewTargets(
        @Parameter(description = "한 번에 받아갈 최대 건수(1..200)", example = "50")
        limit: Int,
    ): ResponseEntity<BaseResponse<AdminFoodContentReviewTargetsResponse>>

    @Operation(
        summary = "승인 결과 반영",
        description = """
            음식 1건의 관리자 판정을 반영한다. 상태 전이는 서버가 수행한다.

            - `passed=true` → **READY**(사용자 조회 노출). 이미 READY 면 멱등 성공.
            - `passed=false` → **FAILED**(관리자 확인 필요). 콘텐츠는 그대로 보존하고 반려 횟수를 1 올린 뒤
              `reason` 을 최대 10줄·1000자까지 저장한다(넘치면 잘라서 보관).
            - PENDING_REVIEW 가 아닌 음식이면 400(COMMON-002) 이다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "반영 성공 — 전이 후 상태·반려 횟수 반환"),
            ApiResponse(
                responseCode = "400",
                description = "검증 실패(COMMON-002 — passed 누락·승인 대상 아님) 또는 음식 없음(FOOD-001)",
                content = [Content(schema = Schema(implementation = BaseResponse::class))],
            ),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun applyContentReviewResult(
        @Parameter(description = "승인 결과를 반영할 음식 id", example = "1")
        foodId: Long,
        @SwaggerRequestBody(
            required = true,
            content = [
                Content(
                    schema = Schema(implementation = AdminFoodContentReviewResultRequest::class),
                    examples = [
                        ExampleObject(name = "승인", value = """{"passed": true}"""),
                        ExampleObject(
                            name = "반려",
                            value = """{"passed": false, "reason": "설명이 음식과 무관함"}""",
                        ),
                    ],
                ),
            ],
        )
        request: AdminFoodContentReviewResultRequest,
    ): ResponseEntity<BaseResponse<AdminFoodContentReviewResultResponse>>
}
