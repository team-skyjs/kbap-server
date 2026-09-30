package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.api.reviewbot.ReviewBotAccountsResult
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 리뷰 봇", description = "관리자 전용 — 리뷰가 빈 READY 음식에 리뷰를 채우는 봇 계정 관리")
interface AdminReviewBotApi {
    @Operation(
        summary = "리뷰 봇 계정 보장(멱등)",
        description = """
            활성 봇 계정이 count 개가 되도록 **모자란 수만** 만든다. 이미 count 이상이면 아무것도 만들지 않는다.

            - 봇은 `is_bot=1`, 국가는 방한 상위국 가중(JP·TW·US·CN·TH 등), 닉네임은 온보딩 기본 규칙(`음식_숫자`).
            - provider_uid 는 `review-bot:<uuid>` 합성값이라 소셜 로그인으로 들어올 수 없고, 기기 등록이 없어 푸시를 받지 않는다.
            - 리뷰 작성은 매일 09~22시 KST 정각 틱의 봇 작성기가 한다(`kbap.review-bot.enabled`).
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "보장 성공 — 전체 봇 목록과 이번에 만든 수"),
        ApiResponse(responseCode = "400", description = "count 누락·범위 밖(1~50)"),
        ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
    )
    @SecurityRequirement(name = "bearerAuth")
    fun ensureBots(request: AdminReviewBotRequest): ResponseEntity<BaseResponse<AdminReviewBotResponse>>
}

@Schema(description = "리뷰 봇 계정 보장 요청")
data class AdminReviewBotRequest(
    @field:NotNull(message = "count 는 필수입니다")
    @field:Min(1, message = "count 는 1 이상이어야 합니다")
    @field:Max(50, message = "count 는 50 이하여야 합니다")
    @field:Schema(description = "유지할 활성 봇 계정 수", example = "15", requiredMode = Schema.RequiredMode.REQUIRED)
    val count: Int?,
)

@Schema(description = "리뷰 봇 계정 목록")
data class AdminReviewBotResponse(
    @field:Schema(description = "이번 호출에서 새로 만든 봇 수", example = "15")
    val createdCount: Int,
    val bots: List<Bot>,
) {
    @Schema(description = "봇 계정")
    data class Bot(
        val memberId: Long,
        val nickname: String?,
        val countryCode: String?,
        val reviewCount: Int,
    )

    companion object {
        fun from(result: ReviewBotAccountsResult): AdminReviewBotResponse =
            AdminReviewBotResponse(
                createdCount = result.createdCount,
                bots = result.bots.map { Bot(it.id, it.nickname, it.countryCode, it.reviewCount) },
            )
    }
}
