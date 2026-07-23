package com.kbap.app.api.admin

import com.kbap.app.api.common.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@Tag(name = "관리자 음식 적재", description = "관리자 전용 — 신규 음식을 INCOMPLETE 로 적재하는 API")
@SecurityRequirement(name = "bearerAuth")
interface AdminApi {
    @Operation(
        summary = "신규 음식 일괄 적재",
        description = """
            관리자가 선정한 한국 음식 메뉴 이름 목록을 제출하면, 기존 food(korean_name)에 없는 이름만
            **INCOMPLETE** 상태로 적재한다. 적재된 음식은 콘텐츠 채움 파이프라인이 이후 완성해 READY 로 전이시킨다.

            - 서버가 각 이름을 스캔 입구와 동일하게 정규화(NFC·한글만 유지)하고 빈 항목·중복을 제거한다 — `requested` 는 정규화 후 판정 대상 수.
            - 멱등: 같은 목록을 재제출해도 중복 행이 생기지 않고 `created=0` 으로 성공한다.
            - **ADMIN 역할 JWT 전용** — USER 토큰은 403(AUTH-008) 으로 거절된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "적재 성공 — requested/created/skipped 카운트 반환"),
            ApiResponse(
                responseCode = "400",
                description = "요청 검증 실패(COMMON-002) — koreanNames 누락·항목 255자 초과",
                content = [Content(schema = Schema(implementation = BaseResponse::class))],
            ),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun seed(
        @SwaggerRequestBody(
            required = true,
            content = [
                Content(
                    schema = Schema(implementation = AdminFoodSeedRequest::class),
                    examples = [
                        ExampleObject(
                            name = "외국인 인기 메뉴 시드",
                            value = """{"koreanNames": ["마라샹궈", "김치찌개", "탕후루"]}""",
                        ),
                    ],
                ),
            ],
        )
        request: AdminFoodSeedRequest,
    ): ResponseEntity<BaseResponse<AdminFoodSeedResponse>>
}
