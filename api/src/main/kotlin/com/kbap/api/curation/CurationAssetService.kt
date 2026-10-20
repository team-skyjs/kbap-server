package com.kbap.api.curation

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.port.storage.ImmutableObjectStore
import com.kbap.common.port.storage.PresignedUploadPort
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

data class CurationUploadResponse(val assetId: Long, val uploadUrl: String, val method: String, val requiredHeaders: Map<String, String>, val contentLength: Long, val expiresAt: Instant, val completeDeadline: Instant)
data class CurationCompleteResponse(val assetId: Long, val state: String, val previewUrl: String)

@Service
class CurationAssetService(private val transactions: CurationAssetTransactions, private val uploads: PresignedUploadPort, private val store: ImmutableObjectStore, private val access: CurationAccess) {
    fun createUpload(adminId: Long, target: String, key: String, version: Long, contentType: String, contentLength: Long): CurationUploadResponse {
        val asset = transactions.reserve(adminId, target, key, version, contentType, contentLength)
        val ttl = Duration.between(LocalDateTime.now(), asset.expiresAt)
        if (ttl.isNegative || ttl.isZero) throw BusinessException(ErrorCode.CURATION_ASSET_INVALID)
        val upload = storage { uploads.issue(asset.stagingKey, contentType, contentLength, ttl) }
        return CurationUploadResponse(asset.assetId, upload.uploadUrl, "PUT", upload.requiredHeaders.filterKeys { !it.equals("Content-Length", true) }, contentLength, asset.expiresAt.atZone(java.time.ZoneId.systemDefault()).toInstant(), asset.completeDeadline.atZone(java.time.ZoneId.systemDefault()).toInstant())
    }

    fun complete(adminId: Long, target: String, key: String, assetId: Long, contentType: String, size: Long): CurationCompleteResponse {
        val asset = transactions.getUpload(adminId, target, key, assetId, contentType, size)
        asset.finalKey?.let { return response(asset.assetId, it) }
        val head = storage { store.inspect(asset.stagingKey) } ?: throw BusinessException(ErrorCode.CURATION_ASSET_INVALID)
        if (head.contentType != contentType || head.sizeBytes != size || head.fingerprint.isBlank()) throw BusinessException(ErrorCode.CURATION_ASSET_INVALID)
        val destination = "curation/${access.environment}/final/${UUID.randomUUID()}"
        storage { store.copyIfUnchanged(asset.stagingKey, destination, head.fingerprint) }
        val final = storage { store.inspect(destination) } ?: throw BusinessException(ErrorCode.CURATION_UNAVAILABLE)
        if (final.contentType != contentType || final.sizeBytes != size) throw BusinessException(ErrorCode.CURATION_UNAVAILABLE)
        val completed = transactions.complete(adminId, target, key, assetId, contentType, size, destination, head.fingerprint)
        return response(assetId, completed.finalKey ?: throw BusinessException(ErrorCode.CURATION_UNAVAILABLE))
    }
    private fun response(id: Long, key: String) = CurationCompleteResponse(id, "READY", access.imageUrl(key))
    private fun <T> storage(call: () -> T): T = try { call() } catch (e: BusinessException) { throw e } catch (e: Exception) { throw BusinessException(ErrorCode.CURATION_UNAVAILABLE) }
}
