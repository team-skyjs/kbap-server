package com.kbap.api.image

import com.kbap.api.common.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "이미지 업로드", description = "이미지 업로드용 presigned URL 발급 API")
@SecurityRequirement(name = "bearerAuth")
interface ImageUploadUrlApi {
    @Operation(
        summary = "업로드용 presigned URL 발급",
        description = """
            인증된 사용자에게 이미지를 스토리지에 직접 올릴 **업로드용 presigned PUT URL** 과, 업로드 후 저장·표시에 쓸
            **만료 없는 안정 공개 URL** 을 함께 발급한다. 로그인 필수 API 다.

            ## 업로드 절차
            1. 이 API 로 `uploadUrl`·`requiredHeaders` 를 받는다.
            2. `uploadUrl` 로 이미지를 PUT 한다 — `requiredHeaders`(Content-Type·Content-Length)를 그대로 실어야 한다(불일치 시 스토리지가 거절).
            3. 업로드 후 `publicUrl`(또는 `objectKey`)을 백엔드 소비 API 에 전달한다. `publicUrl` 은 만료 없이 표시·조회에 재사용한다.

            ## 정책
            용도(purpose)·허용 Content-Type·크기 상한을 발급 단계에서 검증한다. 미지원 용도·형식·크기 초과는 발급을 거절한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "발급 성공 — uploadUrl·publicUrl·objectKey·expiresAt 반환"),
            ApiResponse(responseCode = "400", description = "미지원 용도(UPLOAD-002)·미지원 Content-Type(UPLOAD-001)·크기 초과(UPLOAD-003)·요청 검증 실패"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
        ],
    )
    fun issueUploadUrl(
        memberId: Long,
        request: UploadUrlRequest,
    ): ResponseEntity<BaseResponse<UploadUrlResponse>>
}
