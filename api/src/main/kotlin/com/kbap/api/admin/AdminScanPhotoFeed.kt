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

@Schema(description = "스캔 사진 한 장 — 스캔 업로드 기록(uploaded_image) 한 행당 1건")
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
    @field:Schema(description = "ORDER: 주문 카드로 저장된 사진(묶음 주문의 대표 사진 포함) · BATCH: 여러 장 묶음 스캔에만 쓰인 사진 · SCAN_ONLY: 스캔만 하고 카드는 저장하지 않은 사진")
    val kind: ScanPhotoKind,
    @field:Schema(description = "ORDER 면 주문 id, BATCH 면 스캔 묶음(scan_operation) id, SCAN_ONLY 면 null", nullable = true)
    val sourceId: Long?,
    @field:Schema(description = "MENU: 메뉴판 스캔 사진 · PACKAGED: 가공식품 스캔 사진(앞면·성분표 — kind 는 SCAN_ONLY)")
    val scanType: ScanPhotoType,
    @field:Schema(description = "이름 스캔 확정으로 이 사진과 연결된 가공식품 id — 연결 기록이 있는 경우만(성분표 사진·확정 안 한 사진·기록 이전 사진은 null)", nullable = true)
    val processedFoodId: Long?,
    @field:Schema(description = "연결된 가공식품의 한국어 원명", nullable = true)
    val processedFoodName: String?,
)

enum class ScanPhotoKind { ORDER, BATCH, SCAN_ONLY }

enum class ScanPhotoType { MENU, PACKAGED }

@Schema(description = "스캔 사진 종류 필터 — ALL(기본): 메뉴판·가공식품 모두")
enum class ScanPhotoTypeFilter(val includeMenu: Boolean, val includePackaged: Boolean) {
    ALL(true, true),
    MENU(true, false),
    PACKAGED(false, true),
}
