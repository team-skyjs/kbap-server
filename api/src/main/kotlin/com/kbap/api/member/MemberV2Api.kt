package com.kbap.api.member

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

@Tag(name = "회원 (v2)", description = "프로필 API v2 — 국적 변경 불가")
@SecurityRequirement(name = "bearerAuth")
interface MemberV2Api {
    @Operation(
        summary = "내 프로필 부분 수정 (v2)",
        description = """
            보낸 필드만 갱신하고 보내지 않은 필드는 유지한다(부분 수정). v1 과 달리 **국적(countryCode)은
            수정할 수 없다** — 국적은 최초 온보딩에서 확정되며, 요청에 countryCode 를 포함해 보내도
            알 수 없는 필드로 무시된다(오류 아님). 나머지 필드의 의미·검증은 v1 과 동일하다.
            `Authorization: Bearer {accessToken}` 로 인증한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "수정 완료"),
            ApiResponse(responseCode = "400", description = "입력 검증 실패(기피 성분·닉네임·사진 URL·맵기) 또는 회원을 찾을 수 없음"),
            ApiResponse(responseCode = "401", description = "미인증(토큰 부재·위조·만료)"),
        ],
    )
    fun updateProfile(
        memberId: Long,
        @SwaggerRequestBody(
            required = true,
            content = [
                Content(
                    mediaType = "application/json",
                    examples = [
                        ExampleObject(
                            name = "닉네임·기피 성분 수정 — 국적 필드 없음",
                            value = """
                                {
                                  "nickname": "새닉네임",
                                  "avoidanceSubstanceCodes": ["PEANUT"],
                                  "profileImageUrl": "images/default/profile/profile-default-512.png",
                                  "spicinessPreference": "MILD"
                                }
                            """,
                        ),
                    ],
                ),
            ],
        )
        request: ProfileUpdateV2Request,
    ): ResponseEntity<BaseResponse<Unit>>
}
