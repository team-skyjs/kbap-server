package com.kbap.api.scanlog

import java.time.LocalDateTime

enum class ScanMode { MENU_V1, MENU_V2, PFOOD_NAME, PFOOD_LABEL, PFOOD_VISION }

enum class ScanOutcome { SUCCESS, EMPTY, RETAKE, ERROR }

enum class ScanUiKind { MENU_LIST, EMPTY, PFOOD_CANDIDATES, PFOOD_NO_CANDIDATE, LABEL_RESULT, NOT_PACKAGED, RETAKE, ERROR }

data class ScanResultLogEntry(
    val outcome: ScanOutcome,
    val uiKind: ScanUiKind,
    val errorCode: String? = null,
    val visionKind: String? = null,
    val summary: Map<String, Any?>? = null,
    val foodLinks: List<ScanResultFoodLink> = emptyList(),
)

enum class ScanResultFoodLinkSource { RECORDED, NAME_BACKFILL }

data class ScanResultFoodLink(
    val foodId: Long,
    val matched: Boolean,
    val scannedName: String?,
)

data class ScanResultLogRow(
    val memberId: Long,
    val mode: ScanMode,
    val imagePath: String?,
    val appVersion: String?,
    val apiVersion: String?,
    val entry: ScanResultLogEntry,
    val scannedAt: LocalDateTime,
)
