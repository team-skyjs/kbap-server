package com.kbap.api.image

import com.kbap.api.core.ApiHeaders
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.port.storage.StorageObjectStore
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadPurpose
import com.kbap.common.domain.image.model.UploadedImage
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ImageUploadService(
    private val storageObjectStore: StorageObjectStore,
    private val uploadedImageRepository: UploadedImageJpaRepository,
    private val guestUploadQuota: GuestUploadQuota,
    private val uploadProperties: ImageUploadProperties,
) {
    fun completeUpload(
        memberId: Long?,
        installationId: String?,
        path: String,
        declaredContentType: String,
        declaredSize: Long,
    ): UploadedImage {
        val guestInstallation = guestInstallationOf(memberId, installationId)
        uploadedImageRepository.findByPath(path)?.let { existing ->
            val owned = (memberId != null && existing.isOwnedBy(memberId)) ||
                (guestInstallation != null && existing.isOwnedByInstallation(guestInstallation))
            if (owned) return existing
        }
        val purpose = PresignedUploadService.issuedPurposeOf(path, uploadProperties.keyPrefix, PresignedUploadService.ownerKeyOf(memberId, guestInstallation))
            ?: throw BusinessException(ErrorCode.UPLOAD_PATH_NOT_ISSUED)
        if (guestInstallation != null && purpose != UploadPurpose.FEEDBACK) throw BusinessException(ErrorCode.INVALID_ACCESS_TOKEN)
        return try {
            if (guestInstallation == null) {
                register(memberId, null, path, declaredContentType, declaredSize)
            } else {
                guestUploadQuota.consume(guestInstallation) { register(null, guestInstallation, path, declaredContentType, declaredSize) }
            }
        } catch (e: DataIntegrityViolationException) {
            uploadedImageRepository.findByPath(path)
                ?.takeIf { (memberId != null && it.isOwnedBy(memberId)) || (guestInstallation != null && it.isOwnedByInstallation(guestInstallation)) }
                ?: throw BusinessException(ErrorCode.UPLOADED_OBJECT_NOT_FOUND)
        }
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
        if (actual.lastModified.isBefore(java.time.Instant.now().minus(uploadProperties.completeWindow))) {
            throw BusinessException(ErrorCode.UPLOADED_OBJECT_NOT_FOUND)
        }

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

    private fun guestInstallationOf(memberId: Long?, installationId: String?): String? {
        if (memberId != null) return null
        return installationId?.let(ApiHeaders::validInstallationId)
            ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
    }

    @Transactional(readOnly = true)
    fun verifyImageAccess(memberId: Long, path: String): UploadedImage? =
        uploadedImageRepository.findByPath(path)?.takeIf { it.isOwnedBy(memberId) }

}
