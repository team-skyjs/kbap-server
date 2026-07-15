package com.kbap.app.api.image

import com.kbap.app.api.common.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@Tag(name = "이미지 업로드", description = "서명 URL 업로드 완료 신고·검증 API")
@SecurityRequirement(name = "bearerAuth")
interface ImageApi {
    @Operation(
        summary = "업로드 완료 신고",
        description = """
            서명 URL 로 이미지 업로드를 마친 뒤 호출한다. 서버가 스토리지의 실제 오브젝트를 확인해
            **사진(이미지 형식)인지**와 신고한 형식·크기가 실제와 일치하는지 검증한다.

            - 검증 성공 → 업로드 이미지로 기록하고 경로를 응답한다. 이후 스캔 요청에 이 경로를 넘긴다.
            - 실제 파일이 이미지가 아니거나(영상 등) 신고값과 다르면 → **해당 오브젝트를 스토리지에서 삭제**하고 거절한다.
            - 같은 경로로 다시 신고하면 재검증 없이 동일 결과를 응답한다(멱등).

            크기 상한 정책은 없다(크기는 신고값–실제값 대조용).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "검증 성공 — 이미지 기록"),
            ApiResponse(
                responseCode = "400",
                description = "이미지 아님(IMAGE-001)·신고값 불일치(IMAGE-002)·오브젝트 없음(IMAGE-003)·요청 형식 오류(COMMON-002)",
                content = [Content(schema = Schema(implementation = BaseResponse::class))],
            ),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
        ],
    )
    fun complete(
        memberId: Long,
        @SwaggerRequestBody(required = true)
        request: ImageCompleteRequest,
    ): ResponseEntity<BaseResponse<ImageCompleteResponse>>
}
