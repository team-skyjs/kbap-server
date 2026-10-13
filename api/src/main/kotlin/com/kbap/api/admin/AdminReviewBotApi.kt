package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.api.reviewbot.ReviewBotAccountsResult
import com.kbap.api.reviewbot.ReviewBotPhotoSyncResult
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

            - 봇은 `is_bot=1`, 국가는 방한 상위국 가중(JP·TW·US·CN·TH 등), 닉네임은 서버가 정한다(사람 이름형 소문자, 봇끼리 중복 없음).
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

    @Operation(
        summary = "리뷰 봇 사진 풀 동기화(멱등)",
        description = """
            스토리지 접두 `{key-prefix}/images/review-bot/` 를 훑어 `<key>/<n>.webp`(key = sha256(korean_name)[:12] 소문자 hex) 꼴의
            오브젝트를 `uploaded_image` 에 등록한다. 소유자는 활성 봇 중 id 가 가장 작은 계정. 이미 등록된 경로는 건너뛰어
            재실행해도 행이 늘지 않는다. 기동 시 자동 스캔은 없다 — 사진을 올린 뒤 명시 호출한다(KB-747/KB-748).
            봇 리뷰 작성기는 음식의 풀에서 1~3장을 골라 붙이고, 풀이 빈 음식은 건너뛴다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "동기화 결과 — 등록 수·건너뜀 수·키별 장수"),
        ApiResponse(responseCode = "400", description = "활성 봇 계정이 없어 소유자를 정할 수 없음(COMMON-002) — 먼저 POST /api/admin/review-bots"),
        ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
    )
    @SecurityRequirement(name = "bearerAuth")
    fun syncPhotoPool(): ResponseEntity<BaseResponse<AdminReviewBotPhotoSyncResponse>>
}

@Schema(description = "리뷰 봇 사진 풀 동기화 결과")
data class AdminReviewBotPhotoSyncResponse(
    @field:Schema(description = "이번 호출에서 uploaded_image 에 새로 등록한 사진 수", example = "180")
    val registeredCount: Int,
    @field:Schema(description = "이미 등록돼 건너뛴 사진 수(재실행 시 전부 여기로)", example = "0")
    val skippedCount: Int,
    @field:Schema(description = "풀 키(sha256(korean_name)[:12])별 S3 에 있는 장수", example = """{"6a2c1605affd": 3}""")
    val imagesPerKey: Map<String, Int>,
) {
    companion object {
        fun from(result: ReviewBotPhotoSyncResult): AdminReviewBotPhotoSyncResponse =
            AdminReviewBotPhotoSyncResponse(result.registeredCount, result.skippedCount, result.imagesPerKey)
    }
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
