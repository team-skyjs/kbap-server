package com.kbap.api.curation

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.curation.*
import com.kbap.common.domain.curation.model.*
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CurationService(
    private val releases: CurationReleaseRepository,
    private val collections: CurationCollectionRepository,
    private val assets: CurationAssetRepository,
    private val validation: CurationValidation,
    private val access: CurationAccess,
    private val entityManager: EntityManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun getIndex(requestedLang: String): CurationIndex {
        val root = root()
        val rows = all(root.id)
        val requested = LanguageCode.from(requestedLang).code
        if (root.firstActivatedAt == null || rows.any { !it.isPublished }) return CurationIndex("UNPUBLISHED", root.version, requestedLang, requested, requested != requestedLang, emptyList())
        val docs = rows.map { published(it) }
        val language = resolveLanguage(docs, requested, true)
        return CurationIndex("PUBLISHED", root.version, requestedLang, language, language != requestedLang, rows.zip(docs).map { (row, doc) -> card(row, doc, language, imageUrls(row, listOf(doc.cover!!))) })
    }

    @Transactional(readOnly = true)
    fun getDetail(key: String, requestedLang: String): CurationDetail {
        if (root().firstActivatedAt == null) throw BusinessException(ErrorCode.CURATION_NOT_FOUND)
        val row = collection(key)
        if (!row.isPublished) throw BusinessException(ErrorCode.CURATION_NOT_FOUND)
        val doc = published(row)
        val language = resolveLanguage(listOf(doc), LanguageCode.from(requestedLang).code, false)
        val urls = imageUrls(row, validation.photos(doc).mapNotNull { it.photo })
        val card = card(row, doc, language, urls)
        return CurationDetail(key, card.title, card.description, card.coverImageUrl, card.coverImageAttribution, 10, row.publishedRevision, row.publishedAt ?: unavailable(), requestedLang, language, language != requestedLang,
            doc.items.mapIndexed { index, item -> CurationPublicEntry(item.entryId, index + 1, item.name.getValue(language), item.area.getValue(language), item.introduction.getValue(language), item.recommendationReason.getValue(language), urls.getValue(item.image!!.assetId), attribution(item.image!!), item.externalUrl, item.nearby.map { it.label.getValue(language) }, item.images.ifEmpty { listOf(item.image!!) }.map { CurationPublicImage(urls.getValue(it.assetId), attribution(it)) }) })
    }

    @Transactional(readOnly = true)
    fun getOverview(adminId: Long): CurationAdminOverview {
        access.verifyAdmin(adminId)
        val root = root()
        val rows = all(root.id)
        return CurationAdminOverview(access.environment, state(root, rows), root.version, root.firstActivatedAt, rows.map { CurationAdminSummary(it.collectionKey, it.version, it.isPublished, it.publishedRevision, it.draftDocument != it.publishedDocument, issues(it, false)) })
    }

    @Transactional(readOnly = true)
    fun getAdminDetail(adminId: Long, key: String): CurationAdminDetail {
        access.verifyAdmin(adminId)
        return adminDetail(collection(key))
    }

    @Transactional
    fun updateDraft(adminId: Long, target: String, key: String, expectedVersion: Long, document: CurationDocument): CurationAdminDetail {
        access.verifyAdmin(adminId, target)
        val row = locked(key, expectedVersion)
        val errors = validation.structure(document) + assetIssues(row, document, true, true)
        invalid(errors, row.version)
        if (row.draftDocument == document && row.updatedBy == adminId) entityManager.lock(row, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
        row.updateDraft(validation.copy(document), adminId)
        collections.flush()
        return adminDetail(row)
    }

    @Transactional(readOnly = true)
    fun validate(adminId: Long, target: String, key: String, expectedVersion: Long): CurationValidationResult {
        access.verifyAdmin(adminId, target)
        val row = collection(key)
        version(row, expectedVersion)
        val errors = issues(row, false)
        return CurationValidationResult(errors.isEmpty(), errors, row.version, validation.contentHash(row.draftDocument))
    }

    @Transactional
    fun publish(adminId: Long, target: String, key: String, expectedVersion: Long): CurationAdminDetail {
        access.verifyAdmin(adminId, target)
        val root = releases.lockSeoul() ?: unavailable()
        val rows = publicationRows(root.id)
        val row = rows.find { it.collectionKey == key } ?: throw BusinessException(ErrorCode.CURATION_NOT_FOUND)
        version(row, expectedVersion)
        invalid(issues(row, true), row.version)
        row.publish(validation.copy(row.draftDocument), adminId)
        collections.flush()
        val allPublished = rows.all { it.isPublished && it.publishedDocument != null }
        if (root.firstActivatedAt == null && allPublished) rows.forEach {
            val doc = it.publishedDocument ?: unavailable()
            if (validation.validate(it.collectionKey, doc).isNotEmpty() || assetIssues(it, doc, false, true).isNotEmpty()) unavailable()
        }
        root.changePublication(allPublished)
        releases.flush()
        return adminDetail(row)
    }

    @Transactional
    fun unpublish(adminId: Long, target: String, key: String, expectedVersion: Long): CurationAdminDetail {
        access.verifyAdmin(adminId, target)
        val root = releases.lockSeoul() ?: unavailable()
        val rows = publicationRows(root.id)
        val row = rows.find { it.collectionKey == key } ?: throw BusinessException(ErrorCode.CURATION_NOT_FOUND)
        version(row, expectedVersion)
        row.unpublish(adminId)
        root.changePublication(false)
        collections.flush()
        releases.flush()
        return adminDetail(row)
    }

    private fun adminDetail(row: CurationCollection): CurationAdminDetail {
        val root = root()
        return CurationAdminDetail(access.environment, row.collectionKey, row.version, row.draftDocument, row.publishedDocument, row.publishedSourceVersion, row.publishedRevision, row.isPublished, row.publishedAt, row.publishedBy, row.updatedAt, row.updatedBy, row.unpublishedAt, row.unpublishedBy, row.draftDocument != row.publishedDocument, issues(row, false), validation.contentHash(row.draftDocument), state(root, all(root.id)), root.version, assetPreviews(row))
    }
    private fun assetPreviews(row: CurationCollection): List<CurationAssetPreview> {
        val ids = (validation.photos(row.draftDocument) + row.publishedDocument?.let(validation::photos).orEmpty()).mapNotNull { it.photo?.assetId }.distinct()
        val found = assets.findAllById(ids).associateBy { it.id }
        return ids.map { id ->
            val asset = found[id]?.takeIf { it.collectionId == row.id }
            val key = asset?.finalKey?.takeIf { asset.assetState == "READY" && it.startsWith("curation/${access.environment}/final/") }
            CurationAssetPreview(id, asset?.assetState ?: "MISSING", key?.let(access::imageUrl))
        }
    }
    private fun root(): CurationRelease = releases.findByRegionKey("SEOUL") ?: unavailable()
    private fun publicationRows(releaseId: Long): List<CurationCollection> {
        val rows = collections.lockByReleaseId(releaseId)
        if (rows.size != 3 || rows.map { it.collectionKey }.toSet() != keys) unavailable()
        return rows
    }
    private fun all(releaseId: Long): List<CurationCollection> {
        val rows = collections.findByReleaseId(releaseId)
        if (rows.map { it.collectionKey }.toSet() != keys || rows.size != 3) unavailable()
        return listOf("sights", "stations", "busan").map { key -> rows.single { it.collectionKey == key } }
    }
    private fun collection(key: String): CurationCollection = collections.findByCollectionKey(key) ?: throw BusinessException(ErrorCode.CURATION_NOT_FOUND)
    private fun locked(key: String, expected: Long): CurationCollection = (collections.lockByKey(key) ?: throw BusinessException(ErrorCode.CURATION_NOT_FOUND)).also { version(it, expected) }
    private fun version(row: CurationCollection, expected: Long) {
        if (row.version != expected) throw BusinessException(ErrorCode.CURATION_VERSION_CONFLICT, mapOf("collectionKey" to row.collectionKey, "currentVersion" to row.version))
    }
    private fun state(root: CurationRelease, rows: List<CurationCollection>): String = if (root.firstActivatedAt != null && rows.all { it.isPublished }) "PUBLISHED" else "UNPUBLISHED"
    private fun issues(row: CurationCollection, lock: Boolean): List<CurationIssue> = validation.validate(row.collectionKey, row.draftDocument) + assetIssues(row, row.draftDocument, lock, true)
    private fun assetIssues(row: CurationCollection, document: CurationDocument, lock: Boolean, requireReady: Boolean): List<CurationIssue> {
        val fields = validation.photos(document)
        val ids = fields.mapNotNull { it.photo?.assetId }.distinct().sorted()
        val found = if (lock) ids.associateWith { assets.lockById(it) } else assets.findAllById(ids).associateBy { it.id }
        return fields.mapNotNull { field ->
            val photo = field.photo ?: return@mapNotNull null
            val asset = found[photo.assetId]
            val reason = when {
                asset == null || asset.collectionId != row.id || !asset.stagingKey.startsWith("curation/${access.environment}/staging/") -> "ASSET_TARGET_MISMATCH"
                asset.assetState in setOf("DELETING", "DELETED", "EXPIRED", "REJECTED") -> "ASSET_NOT_READY"
                requireReady && (asset.assetState != "READY" || asset.finalKey == null || asset.sourceFingerprint == null) -> "ASSET_NOT_READY"
                else -> null
            }
            reason?.let { CurationIssue(field.path, it, field.entryId) }
        }
    }
    private fun published(row: CurationCollection): CurationDocument {
        val doc = row.publishedDocument ?: unavailable()
        if (doc.items.size != CurationValidation.ITEM_COUNT || validation.photos(doc).any { it.photo == null } || assetIssues(row, doc, false, true).isNotEmpty()) unavailable()
        return doc
    }
    private fun resolveLanguage(docs: List<CurationDocument>, requested: String, cardsOnly: Boolean): String {
        if (docs.all { validation.hasLanguage(it, requested, cardsOnly) }) return requested
        if (!docs.all { validation.hasLanguage(it, "en", cardsOnly) }) unavailable()
        log.warn("Curation translation fallback requestedLang={}", requested)
        return "en"
    }
    private fun card(row: CurationCollection, doc: CurationDocument, language: String, urls: Map<Long, String>) = CurationCard(row.collectionKey, doc.title.getValue(language), doc.description.getValue(language), urls.getValue(doc.cover!!.assetId), attribution(doc.cover!!), 10, row.publishedRevision)
    private fun attribution(photo: CurationPhoto) = photo.rights.attributionText.takeIf { photo.rights.attributionRequirement == AttributionRequirement.TEXT }
    private fun imageUrls(row: CurationCollection, photos: List<CurationPhoto>): Map<Long, String> {
        val found = assets.findAllById(photos.map { it.assetId }.distinct()).associateBy { it.id }
        return photos.associate { photo ->
            val asset = found[photo.assetId] ?: unavailable()
            if (asset.collectionId != row.id || asset.assetState != "READY") unavailable()
            photo.assetId to access.imageUrl(asset.finalKey ?: unavailable())
        }
    }
    private fun invalid(errors: List<CurationIssue>, version: Long) {
        if (errors.isNotEmpty()) throw BusinessException(ErrorCode.CURATION_PUBLICATION_INVALID, mapOf("errors" to errors, "version" to version))
    }
    private fun unavailable(): Nothing = throw BusinessException(ErrorCode.CURATION_UNAVAILABLE)
    companion object { val keys = setOf("sights", "stations", "busan") }
}
