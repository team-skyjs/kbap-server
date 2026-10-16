package com.kbap.api.curation

import com.kbap.common.domain.curation.model.CurationDocument
import java.time.LocalDateTime

data class CurationIndex(val state: String, val sectionVersion: Long, val requestedLang: String, val resolvedLang: String, val fallbackUsed: Boolean, val collections: List<CurationCard>)
data class CurationCard(val collectionKey: String, val title: String, val description: String, val coverImageUrl: String, val itemCount: Int, val publishedRevision: Long)
data class CurationDetail(val collectionKey: String, val title: String, val description: String, val coverImageUrl: String, val itemCount: Int, val publishedRevision: Long, val publishedAt: LocalDateTime, val requestedLang: String, val resolvedLang: String, val fallbackUsed: Boolean, val items: List<CurationPublicEntry>)
data class CurationPublicEntry(val entryId: String, val position: Int, val name: String, val area: String, val introduction: String, val recommendationReason: String, val imageUrl: String, val externalUrl: String, val nearby: List<String>)
data class CurationValidationResult(val valid: Boolean, val errors: List<CurationIssue>, val version: Long, val requiredContentHash: String)
data class CurationAdminDetail(
    val environment: String,
    val collectionKey: String,
    val version: Long,
    val draftDocument: CurationDocument,
    val publishedDocument: CurationDocument?,
    val publishedSourceVersion: Long?,
    val publishedRevision: Long,
    val isPublished: Boolean,
    val publishedAt: LocalDateTime?,
    val publishedBy: Long?,
    val updatedAt: LocalDateTime,
    val updatedBy: Long?,
    val unpublishedAt: LocalDateTime?,
    val unpublishedBy: Long?,
    val hasUnpublishedChanges: Boolean,
    val validationErrors: List<CurationIssue>,
    val requiredContentHash: String,
    val sectionState: String,
    val sectionVersion: Long,
    val assets: List<CurationAssetPreview>,
)
data class CurationAdminOverview(val environment: String, val sectionState: String, val sectionVersion: Long, val firstActivatedAt: LocalDateTime?, val collections: List<CurationAdminSummary>)
data class CurationAdminSummary(val collectionKey: String, val version: Long, val isPublished: Boolean, val publishedRevision: Long, val hasUnpublishedChanges: Boolean, val validationErrors: List<CurationIssue>)

data class CurationAssetPreview(val assetId: Long, val state: String, val previewUrl: String?)
