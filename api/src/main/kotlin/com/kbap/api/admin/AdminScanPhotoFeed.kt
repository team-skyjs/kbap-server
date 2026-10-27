package com.kbap.api.admin

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "전체 스캔 사진 피드 한 페이지(최신순 24장) — 커서 방식, 총 건수 없음")
data class AdminScanPhotoFeed(
    val items: List<AdminScanPhotoFeedItem>,
    @field:Schema(description = "다음 페이지 커서 — 다음 요청의 cursor 로 그대로 보낸다. 마지막 페이지면 null", nullable = true)
    val nextCursor: String?,
    val hasNext: Boolean,
)

@Schema(description = "스캔 사진 한 장 — 출처 행 단위(같은 사진이 두 묶음에 쓰이면 2건)")
data class AdminScanPhotoFeedItem(
    @field:Schema(description = "사진 URL", nullable = true)
    val imageUrl: String?,
    val createdAt: LocalDateTime,
    val memberId: Long,
    @field:Schema(nullable = true)
    val nickname: String?,
    @field:Schema(nullable = true)
    val countryCode: String?,
    val isBot: Boolean,
    @field:Schema(description = "ACTIVE·SUSPENDED·DELETED(탈퇴)", example = "ACTIVE")
    val memberStatus: String,
    @field:Schema(description = "ORDER: 단일 스캔(주문) · BATCH: 여러 장 묶음 스캔(묶음 주문의 대표 사진 포함)")
    val kind: ScanPhotoKind,
    @field:Schema(description = "ORDER 면 주문 id, BATCH 면 스캔 묶음(scan_operation) id")
    val sourceId: Long,
)

enum class ScanPhotoKind { ORDER, BATCH }
