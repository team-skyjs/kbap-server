package com.kbap.domain.image

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.storage.StorageObjectStore
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadedImage
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ImageUploadService(
    private val storageObjectStore: StorageObjectStore,
    private val uploadedImageRepository: UploadedImageJpaRepository,
) {
    // 의도적 무트랜잭션 — HeadObject/DeleteObject 외부 호출을 트랜잭션 밖에 두고(헌법: 외부 호출 tx 밖),
    // 검증 통과분만 단건 저장한다. 검증 실패 시 오브젝트를 지우는 것은 롤백이 아니라 의도된 정리다.
    fun completeUpload(memberId: Long, path: String, declaredContentType: String, declaredSize: Long): UploadedImage {
        uploadedImageRepository.findByPath(path)?.let { existing ->
            if (existing.isOwnedBy(memberId)) return existing
            throw BusinessException(ErrorCode.UPLOADED_OBJECT_NOT_FOUND)
        }

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
                path = path,
                contentType = actual.contentType,
                sizeBytes = actual.sizeBytes,
            ),
        )
    }

    @Transactional(readOnly = true)
    fun verifyImageAccess(memberId: Long, path: String): UploadedImage? =
        uploadedImageRepository.findByPath(path)?.takeIf { it.isOwnedBy(memberId) }
}
