package com.kbap.api.image

import com.kbap.api.core.ApiHeaders
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.port.storage.StorageObjectStore
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadedImage
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class ImageUploadService(
    private val storageObjectStore: StorageObjectStore,
    private val uploadedImageRepository: UploadedImageJpaRepository,
) {
    // 의도적 무트랜잭션 — HeadObject/DeleteObject 외부 호출을 트랜잭션 밖에 두고(헌법: 외부 호출 tx 밖),
    // 검증 통과분만 단건 저장한다. 검증 실패 시 오브젝트를 지우는 것은 롤백이 아니라 의도된 정리다.
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
        if (guestInstallation != null) verifyGuestQuota(guestInstallation)

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

    // 익명 완료는 문의 사진 경로에만 연다 — 다른 purpose 는 토큰이 필요하다.
    private fun guestInstallationOf(memberId: Long?, installationId: String?, path: String): String? {
        if (memberId != null) return null
        if (!path.contains(FEEDBACK_SEGMENT)) throw BusinessException(ErrorCode.INVALID_ACCESS_TOKEN)
        return installationId?.let(ApiHeaders::validInstallationId)
            ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
    }

    private fun verifyGuestQuota(installationId: String) {
        val since = LocalDateTime.now().minusDays(1)
        if (uploadedImageRepository.countByInstallationIdAndCreatedAtAfter(installationId, since) >= GUEST_DAILY_LIMIT) {
            throw BusinessException(ErrorCode.IMAGE_UPLOAD_RATE_LIMITED)
        }
    }

    @Transactional(readOnly = true)
    fun verifyImageAccess(memberId: Long, path: String): UploadedImage? =
        uploadedImageRepository.findByPath(path)?.takeIf { it.isOwnedBy(memberId) }

    companion object {
        const val GUEST_DAILY_LIMIT = 10
        private const val FEEDBACK_SEGMENT = "images/feedback/"
    }
}
