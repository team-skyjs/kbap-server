package com.kbap.api.image

import com.kbap.api.core.ApiHeaders
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.port.storage.StorageObjectStore
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadedImage
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ImageUploadService(
    private val storageObjectStore: StorageObjectStore,
    private val uploadedImageRepository: UploadedImageJpaRepository,
    private val guestUploadQuota: GuestUploadQuota,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    fun completeUpload(
        memberId: Long?,
        installationId: String?,
        path: String,
        declaredContentType: String,
        declaredSize: Long,
    ): UploadedImage {
        val guestInstallation = guestInstallationOf(memberId, installationId, path)
        uploadedImageRepository.findByPath(path)?.let { existing ->
            val owned = (memberId != null && existing.isOwnedBy(memberId)) ||
                (guestInstallation != null && existing.isOwnedByInstallation(guestInstallation))
            if (owned) return existing
            throw BusinessException(ErrorCode.UPLOADED_OBJECT_NOT_FOUND)
        }
        if (guestInstallation == null) return register(memberId, null, path, declaredContentType, declaredSize)
        return try {
            guestUploadQuota.consume(guestInstallation) {
                register(null, guestInstallation, path, declaredContentType, declaredSize)
            }
        } catch (e: BusinessException) {
            if (e.errorCode == ErrorCode.IMAGE_UPLOAD_RATE_LIMITED) discardUnrecorded(path)
            throw e
        }
    }

    private fun discardUnrecorded(path: String) {
        runCatching { storageObjectStore.delete(path) }
            .onFailure { log.warn("한도 초과로 거절된 업로드 오브젝트를 지우지 못했다 — 정리 잡이 뒤에 처리한다 path={}", path, it) }
    }

    private fun register(
        memberId: Long?,
        guestInstallation: String?,
        path: String,
        declaredContentType: String,
        declaredSize: Long,
    ): UploadedImage {
        val actual = storageObjectStore.head(path)
            ?: throw BusinessException(ErrorCode.UPLOADED_OBJECT_NOT_FOUND)

        if (!actual.contentType.startsWith("image/")) {
            storageObjectStore.delete(path)
            throw BusinessException(ErrorCode.NOT_IMAGE_FILE)
        }
        if (actual.contentType != declaredContentType || actual.sizeBytes != declaredSize) {
            storageObjectStore.delete(path)
            throw BusinessException(ErrorCode.UPLOAD_MISMATCH)
        }

        return uploadedImageRepository.save(
            UploadedImage(
                memberId = memberId,
                installationId = guestInstallation,
                path = path,
                contentType = actual.contentType,
                sizeBytes = actual.sizeBytes,
            ),
        )
    }

    private fun guestInstallationOf(memberId: Long?, installationId: String?, path: String): String? {
        if (memberId != null) return null
        if (!path.contains(FEEDBACK_SEGMENT)) throw BusinessException(ErrorCode.INVALID_ACCESS_TOKEN)
        return installationId?.let(ApiHeaders::validInstallationId)
            ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
    }

    @Transactional(readOnly = true)
    fun verifyImageAccess(memberId: Long, path: String): UploadedImage? =
        uploadedImageRepository.findByPath(path)?.takeIf { it.isOwnedBy(memberId) }

    companion object {
        private const val FEEDBACK_SEGMENT = "images/feedback/"
    }
}
