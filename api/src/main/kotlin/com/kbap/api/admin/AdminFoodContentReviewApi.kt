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

@Tag(name = "관리자 음식 AI 검수", description = "관리자 전용 — 외부 AI 검수 파이프라인이 검수 대상을 받아가고 결과를 반영하는 API")
@SecurityRequirement(name = "bearerAuth")
interface AdminFoodContentReviewApi {
    @Operation(
        summary = "검수 대상 음식 조회",
        description = """
            콘텐츠가 모두 채워져 검수를 기다리는 **PENDING_REVIEW** 음식을 판단에 필요한 필드와 함께 반환한다.

            - `avoidanceSubstances` 는 미조사(null)여도 빈 배열로 내려간다 — PENDING_REVIEW 는 이미 조사가 끝난 상태다.
            - `imageUrl` 은 공개 이미지 URL 이며, 이미지가 없으면 null 이다.
            - `contentReviewAttempts` 는 지금까지의 AI 검수 탈락 횟수다(0·1). 2 이상은 REVIEW_REJECTED 로 빠져 대상에 없다.
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
        summary = "검수 결과 반영",
        description = """
            음식 1건의 AI 검수 판정을 반영한다. 상태 전이·컬럼 롤백·시도 횟수 증가는 모두 서버가 수행한다.

            - `passed=true` → **REVIEWED**(사람 승인 대기). 콘텐츠·시도 횟수는 그대로. 이미 REVIEWED 면 멱등 성공.
            - `passed=false` 이고 `contentReviewAttempts < 2` → `rejectedFields` 로 지목된 필드만 미채움으로 되돌리고
              시도 횟수를 1 올린 뒤 **INCOMPLETE**(이미지만 문제면 **PENDING_IMAGE**)로 롤백. 콘텐츠 채움 배치가 재생성한다.
            - `passed=false` 이고 `contentReviewAttempts >= 2` → 콘텐츠를 그대로 둔 채 **REVIEW_REJECTED** 로 전이하고
              `reason` 을 최대 10줄·1000자까지 저장한다(넘치면 잘라서 보관, 이후 사람이 판단).
            - 탈락인데 `rejectedFields` 가 비었거나, PENDING_REVIEW 가 아닌 음식이면 400(COMMON-002) 이다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "반영 성공 — 전이 후 상태·시도 횟수 반환"),
            ApiResponse(
                responseCode = "400",
                description = "검증 실패(COMMON-002 — passed 누락·rejectedFields 없음·검수 대상 아님) 또는 음식 없음(FOOD-001)",
                content = [Content(schema = Schema(implementation = BaseResponse::class))],
            ),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun applyContentReviewResult(
        @Parameter(description = "검수 결과를 반영할 음식 id", example = "1")
        foodId: Long,
        @SwaggerRequestBody(
            required = true,
            content = [
                Content(
                    schema = Schema(implementation = AdminFoodContentReviewResultRequest::class),
                    examples = [
                        ExampleObject(name = "통과", value = """{"passed": true}"""),
                        ExampleObject(
                            name = "탈락 — 설명·설명 번역 재생성",
                            value = """{"passed": false, "rejectedFields": ["DESCRIPTION", "DESCRIPTION_TRANSLATIONS"], "reason": "설명이 음식과 무관함"}""",
                        ),
                    ],
                ),
            ],
        )
        request: AdminFoodContentReviewResultRequest,
    ): ResponseEntity<BaseResponse<AdminFoodContentReviewResultResponse>>
}
