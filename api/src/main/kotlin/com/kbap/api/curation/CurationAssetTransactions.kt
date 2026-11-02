package com.kbap.api.curation

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.curation.CurationAssetRepository
import com.kbap.common.domain.curation.CurationCollectionRepository
import com.kbap.common.domain.curation.model.CurationAsset
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

data class CurationAssetState(val assetId: Long, val stagingKey: String, val contentType: String, val size: Long, val expiresAt: LocalDateTime, val completeDeadline: LocalDateTime, val finalKey: String?)

@Service
class CurationAssetTransactions(private val assets: CurationAssetRepository, private val collections: CurationCollectionRepository, private val access: CurationAccess) {
    @Transactional
    fun reserve(adminId: Long, target: String, key: String, expectedVersion: Long, contentType: String, size: Long): CurationAssetState {
        access.verifyAdmin(adminId, target)
        verifySize(contentType, size)
        val collection = collections.lockByKey(key) ?: throw BusinessException(ErrorCode.CURATION_NOT_FOUND)
        if (collection.version != expectedVersion) throw BusinessException(ErrorCode.CURATION_VERSION_CONFLICT, mapOf("currentVersion" to collection.version, "collectionKey" to key))
        val expiry = LocalDateTime.now().plusMinutes(5)
        val asset = assets.save(CurationAsset(collectionId = collection.id, issuedByAdminId = adminId, stagingKey = "curation/${access.environment}/staging/${UUID.randomUUID()}", contentType = contentType, sizeBytes = size, expiresAt = expiry, completeDeadline = expiry.plusDays(1)))
        return state(asset)
    }

    @Transactional(readOnly = true)
    fun getUpload(adminId: Long, target: String, key: String, assetId: Long, contentType: String, size: Long): CurationAssetState {
        access.verifyAdmin(adminId, target)
        val asset = assets.findById(assetId).orElse(null) ?: invalid()
        verifyOwner(asset, adminId, key, contentType, size)
        return state(asset)
    }

    @Transactional
    fun complete(adminId: Long, target: String, key: String, assetId: Long, contentType: String, size: Long, finalKey: String, fingerprint: String): CurationAssetState {
        access.verifyAdmin(adminId, target)
        val asset = assets.lockById(assetId) ?: invalid()
        verifyOwner(asset, adminId, key, contentType, size)
        if (asset.assetState != "READY") {
            if (!finalKey.startsWith("curation/${access.environment}/final/") || fingerprint.isBlank() || fingerprint.length > 255) invalid()
            asset.complete(finalKey, fingerprint, LocalDateTime.now())
        }
        return state(asset)
    }

    @Transactional
    fun attachDisplayVariant(assetId: Long, finalKey: String, displayKey: String) {
        val asset = assets.lockById(assetId) ?: return
        if (asset.finalKey == finalKey && asset.displayKey == null) asset.attachDisplayVariant(displayKey)
    }

    @Transactional(readOnly = true)
    fun getDisplayVariantCandidates(): List<Pair<Long, String>> =
        assets.findByAssetStateAndDisplayKeyIsNullAndFinalKeyStartingWithOrderById("READY", "curation/${access.environment}/final/")
            .mapNotNull { asset -> asset.finalKey?.let { asset.id to it } }

    private fun verifyOwner(asset: CurationAsset, adminId: Long, key: String, contentType: String, size: Long) {
        val collection = collections.findByCollectionKey(key) ?: invalid()
        if (asset.issuedByAdminId != adminId || asset.collectionId != collection.id || !asset.stagingKey.startsWith("curation/${access.environment}/staging/") || asset.contentType != contentType || asset.sizeBytes != size) invalid()
        if (asset.assetState !in setOf("ISSUED", "READY")) invalid()
        if (asset.assetState != "READY" && !LocalDateTime.now().isBefore(asset.completeDeadline)) invalid()
    }
    private fun state(a: CurationAsset) = CurationAssetState(a.id, a.stagingKey, a.contentType, a.sizeBytes, a.expiresAt, a.completeDeadline, a.finalKey)
    private fun verifySize(type: String, size: Long) {
        if (type !in setOf("image/jpeg", "image/png", "image/webp") || size !in 1..10485760L) invalid()
    }
    private fun invalid(): Nothing = throw BusinessException(ErrorCode.CURATION_ASSET_INVALID)
}
