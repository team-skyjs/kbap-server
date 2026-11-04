package com.kbap.api.admin

import com.kbap.api.scanlog.ScanMode
import com.kbap.api.scanlog.ScanOutcome
import com.kbap.api.scanlog.ScanUiKind
import com.kbap.common.port.llm.ProcessedFoodPhotoKind
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
    @field:Schema(description = "업로드 기록 id — 스캔 결과 상세(GET /api/admin/scan-photos/{uploadedImageId}/scan-results)의 경로 값")
    val uploadedImageId: Long,
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
    @field:Schema(description = "사진 스캔(scan/vision)이 판별한 종류 — MENU·PACKAGE_FRONT·INGREDIENT_LABEL·OTHER. 사진 스캔을 거치지 않은 사진은 null", nullable = true)
    val visionKind: ProcessedFoodPhotoKind? = null,
    @field:Schema(description = "이 사진의 최신 스캔 결과 기록(KB-983) — SUCCESS·EMPTY·RETAKE·ERROR. 기록 이전 사진·기록 없는 사진은 null", nullable = true)
    val scanOutcome: ScanOutcome? = null,
    @field:Schema(description = "최신 기록 기준 앱이 띄운 화면 종류 — MENU_LIST·EMPTY·PFOOD_CANDIDATES·PFOOD_NO_CANDIDATE·LABEL_RESULT·NOT_PACKAGED·RETAKE·ERROR", nullable = true)
    val uiKind: ScanUiKind? = null,
)

enum class ScanPhotoKind { ORDER, BATCH, SCAN_ONLY }

enum class ScanPhotoType { MENU, PACKAGED }

@Schema(description = "스캔 사진 종류 필터 — ALL(기본): 메뉴판·가공식품 모두")
enum class ScanPhotoTypeFilter(val includeMenu: Boolean, val includePackaged: Boolean) {
    ALL(true, true),
    MENU(true, false),
    PACKAGED(false, true),
}

@Schema(description = "사진 한 장의 스캔 결과 기록 전체 — 스캔 완료 시각 최신순(최대 50건)")
data class AdminScanResults(
    val records: List<AdminScanResultRecord>,
)

@Schema(description = "스캔 요청 1건의 결과 기록(scan_result_log 한 행)")
data class AdminScanResultRecord(
    val id: Long,
    val memberId: Long,
    @field:Schema(description = "MENU_V1(/api/scans 무버전)·MENU_V2(/api/scans 2.0+)·PFOOD_NAME·PFOOD_LABEL·PFOOD_VISION")
    val scanMode: ScanMode,
    val outcome: ScanOutcome,
    @field:Schema(description = "실패 응답의 에러 코드(예: SCAN-003). 비즈니스 예외가 아닌 실패는 예외 클래스 이름", nullable = true)
    val errorCode: String?,
    val uiKind: ScanUiKind,
    @field:Schema(description = "사진 스캔(PFOOD_VISION)이 판별한 종류", nullable = true)
    val visionKind: String?,
    @field:Schema(description = "X-App-Version 헤더", nullable = true)
    val appVersion: String?,
    @field:Schema(description = "X-API-Version 헤더", nullable = true)
    val apiVersion: String?,
    @field:Schema(
        description = "응답 요약. 메뉴판: degraded·items/photoOnly(name·matched·foodId·riskLevel·substituted — matched=false·foodId null = DB 에 준비된 음식이 없는 메뉴)·itemCount/photoOnlyCount. " +
            "이름 스캔: state·candidates(id·reportNo·productName). 성분표: state·labelSource·verdict·detections·unmapped. 사진 스캔: kind·confidence·reason·state·front·label. " +
            "16,000자를 넘으면 목록을 30·10·3건으로 차례로 줄이고(그래도 넘으면 목록을 뺀다) truncated=true 를 단다. 개수 필드는 원래 값이다. 실패 기록은 null",
        nullable = true,
    )
    val responseSummary: Map<String, Any?>?,
    @field:Schema(description = "요청 스레드에서 스캔이 끝난 시각 — 최신 판정·정렬 기준(적재는 비동기라 id·createdAt 순서와 다를 수 있다)")
    val scannedAt: LocalDateTime,
    @field:Schema(description = "기록이 적재된 시각")
    val createdAt: LocalDateTime,
    @field:Schema(description = "이 스캔의 미매칭 메뉴(matched=false) 중 LLM 추정이 캐시된 것(S-959) — kind=NOT_FOOD 면 그 이름의 음식 행은 검수 대상이 아니다")
    val menuEstimates: List<AdminMenuEstimate> = emptyList(),
)

@Schema(description = "메뉴 이름 LLM 추정(menu_name_estimate 한 행)")
data class AdminMenuEstimate(
    @field:Schema(description = "메뉴판에서 읽은 이름(요약의 name)")
    val name: String,
    val kind: String,
    @field:Schema(nullable = true)
    val englishName: String?,
    @field:Schema(nullable = true)
    val description: String?,
    val avoidanceCodes: List<String>,
    val spiciness: Int,
    @field:Schema(nullable = true)
    val confidence: Double?,
    val model: String,
    @field:Schema(description = "추정에 쓴 프롬프트 판")
    val promptVersion: String,
)
