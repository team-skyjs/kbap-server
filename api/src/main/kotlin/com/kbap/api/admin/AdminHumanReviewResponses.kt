package com.kbap.api.admin

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "검수한 관리자")
data class AdminReviewerResponse(
    @field:Schema(description = "관리자 계정 id", example = "2")
    val id: Long,
    @field:Schema(description = "표시 이름. 계정에 표시 이름이 없으면 로그인 아이디", example = "김예진")
    val displayName: String,
)

@Schema(description = "사람 검수 기록 — 최신 검수자·시각만(이력 없음)")
data class HumanReviewResponse(
    val reviewedBy: AdminReviewerResponse,
    @field:Schema(description = "검수 시각", example = "2026-09-22T22:50:00")
    val reviewedAt: LocalDateTime,
)

@Schema(description = "사람 검수 기록·해제 응답")
data class AdminFoodHumanReviewResponse(
    @field:Schema(description = "음식 id", example = "42")
    val foodId: Long,
    @field:Schema(description = "기록 후 상태. 해제하면 null", nullable = true)
    val humanReview: HumanReviewResponse?,
)

@Schema(description = "검수 기록 목록 항목")
data class AdminHumanReviewItemResponse(
    @field:Schema(description = "음식 id — 행 탭 시 음식 상세로", example = "42")
    val foodId: Long,
    @field:Schema(description = "음식 한국어 이름", example = "순두부찌개")
    val name: String,
    val reviewer: AdminReviewerResponse,
    val reviewedAt: LocalDateTime,
)

@Schema(description = "관리자별 검수 건수")
data class AdminHumanReviewSummaryResponse(
    val adminId: Long,
    val displayName: String,
    @field:Schema(example = "17")
    val count: Long,
)

@Schema(description = "검수 기록 탭 — 관리자별 건수 + 최신순 목록(커서)")
data class AdminHumanReviewListResponse(
    val items: List<AdminHumanReviewItemResponse>,
    val hasNext: Boolean,
    @field:Schema(description = "다음 페이지 커서(마지막 항목의 foodId). 마지막 페이지면 null", nullable = true)
    val nextCursor: Long?,
    @field:Schema(description = "관리자별 검수 건수 — adminId 필터와 무관하게 전체 기준, 건수 내림차순")
    val summary: List<AdminHumanReviewSummaryResponse>,
)

@Schema(description = "현재 로그인한 관리자")
data class AdminMeResponse(
    val id: Long,
    val loginId: String,
    @field:Schema(description = "표시 이름. 계정에 표시 이름이 없으면 로그인 아이디", example = "김예진")
    val displayName: String,
)
