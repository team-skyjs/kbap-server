package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.api.reviewbot.ReviewBotAccountsResult
import com.kbap.api.reviewbot.ReviewBotPhotoSyncResult
import com.kbap.api.reviewbot.ReviewBotPhotoInventory
import com.kbap.api.reviewbot.ReviewBotPhotoHold
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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

    @Operation(summary = "리뷰 봇 사진 재고 조회", description = "사용 가능·미등록·보류 사진을 조회한다. 등록행·리뷰·저장소를 변경하지 않는다.")
    @SecurityRequirement(name = "bearerAuth")
    fun getPhotoInventory(): ResponseEntity<BaseResponse<ReviewBotPhotoInventory>>

    @Operation(
        summary = "리뷰 봇 사진 등록 및 보류 보고",
        description = """
            스토리지의 {key-prefix}/images/review-bot/<key>/<n>.webp 를 활성 봇 소유로 등록한다.
            key는 sha256(korean_name)[:12] 소문자 hex다. 별도 photo-sync-enabled 설정이 켜져 있어야 등록할 수 있다.
            소실·일반 참조·소유 또는 상태 충돌은 held에 보고한다. 등록행 복구, 소유 변경, 사진 분리, 리뷰 삭제는 수행하지 않는다.
            기존 리뷰는 그대로 남아 소실 사진이 깨져 보일 수 있다. 새 리뷰에는 사용 가능한 사진만 1~3장 붙인다.
            기존 force 파라미터는 호환을 위해 받지만 삭제·복구를 허용하지 않는다. 삭제·복구 관련 카운터는 항상 0이다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "등록 수·보류 사유 및 호환 카운터"),
        ApiResponse(responseCode = "400", description = "등록 기능이 꺼져 있거나 활성 봇 계정이 없음(COMMON-002)"),
        ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
    )
    @SecurityRequirement(name = "bearerAuth")
    fun syncPhotoPool(
        @Parameter(description = "호환용 파라미터. true여도 자동 삭제·복구하지 않는다", example = "false")
        force: Boolean,
    ): ResponseEntity<BaseResponse<AdminReviewBotPhotoSyncResponse>>
}

@Schema(description = "리뷰 봇 사진 등록 및 보류 결과")
data class AdminReviewBotPhotoSyncResponse(
    val registeredCount: Int,
    val skippedCount: Int,
    val restoredCount: Int,
    val removedCount: Int,
    val detachedReviewCount: Int,
    val deletedReviewCount: Int,
    val failedReviewCount: Int,
    val unparseableReviewIds: List<Long>,
    val detectionFailed: Boolean,
    val removalSkipped: Boolean,
    val imagesPerKey: Map<String, Int>,
    val held: List<ReviewBotPhotoHold>,
) {
    companion object {
        fun from(result: ReviewBotPhotoSyncResult): AdminReviewBotPhotoSyncResponse =
            AdminReviewBotPhotoSyncResponse(
                registeredCount = result.registeredCount,
                skippedCount = result.skippedCount,
                restoredCount = 0,
                removedCount = 0,
                detachedReviewCount = 0,
                deletedReviewCount = 0,
                failedReviewCount = 0,
                unparseableReviewIds = emptyList(),
                detectionFailed = false,
                removalSkipped = true,
                imagesPerKey = result.imagesPerKey,
                held = result.held,
            )
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
