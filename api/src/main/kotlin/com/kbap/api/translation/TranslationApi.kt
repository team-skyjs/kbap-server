package com.kbap.api.translation

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

@Tag(name = "번역", description = "사용자 글을 보는 사람의 앱 언어로 번역한다 — 지금 대상은 리뷰 본문(REVIEW)")
@SecurityRequirement(name = "bearerAuth")
interface TranslationApi {
    @Operation(
        summary = "글 번역",
        description = """
            `targetType` + `targetId` 로 가리킨 글의 본문을 `lang` 언어로 번역해 준다. **게스트도 쓸 수 있다**(토큰은 선택 — 보내면 검증한다).

            - `lang` 은 필수이며 다른 API 와 같은 규칙이다 — 지원하지 않는 값은 영어(`en`)로 풀리고 응답 `language` 에 풀린 값이 온다.
            - 같은 (대상, 언어)의 번역은 서버가 저장해 두고 다시 쓴다. 글이 수정되면 다음 요청에서 다시 번역한다.
            - **목록에서 볼 수 없는 글은 번역도 안 된다** — 삭제된 리뷰, 삭제된 음식의 리뷰, 내가 차단한 회원의 리뷰는 400(REVIEW-001).
              본인이 쓴 리뷰는 음식이 삭제됐어도 번역된다(내 리뷰 목록에 보이므로).
            - 본문이 빈 글은 200 이고 `text` 가 빈 문자열이다.
            - 번역 엔진 실패·시간 초과는 503(TRANSLATION-001)이다 — 원문을 그대로 두고 다시 시도하게 한다.
            - 모르는 `targetType`·누락 필드·`lang` 누락은 400(COMMON-002).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "번역 성공(본문이 빈 글이면 text 가 빈 문자열)"),
            ApiResponse(responseCode = "400", description = "잘못된 요청(COMMON-002) · 볼 수 없는 리뷰(REVIEW-001)"),
            ApiResponse(responseCode = "401", description = "보낸 토큰이 위조·만료"),
            ApiResponse(responseCode = "503", description = "번역 엔진 일시 실패(TRANSLATION-001)"),
        ],
    )
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.REVIEW_NOT_FOUND, ErrorCode.TRANSLATION_FAILED)
    fun translate(
        memberId: Long?,
        @Parameter(description = "번역할 언어 코드(앱 UI 언어). 미지원 값은 en 으로 풀린다", required = true, example = "ko") lang: String,
        request: TranslationRequest,
    ): ResponseEntity<BaseResponse<TranslationResponse>>
}
