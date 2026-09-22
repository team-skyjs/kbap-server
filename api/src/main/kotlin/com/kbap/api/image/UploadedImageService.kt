package com.kbap.api.image

import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadPurpose
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UploadedImageService(
    private val uploadedImageRepository: UploadedImageJpaRepository,
) {
    @Transactional
    fun ownsAllImages(
        memberId: Long?,
        paths: List<String>?,
        purpose: UploadPurpose,
        installationId: String? = null,
    ): Boolean {
        if (paths.isNullOrEmpty()) return true
        if (memberId == null && installationId == null) return false
        val segment = "images/${purpose.prefix}/"
        val ownedPaths = uploadedImageRepository.findByPathInForUpdate(paths)
            .filter { image ->
                image.path.contains(segment) &&
                    ((memberId != null && image.isOwnedBy(memberId)) ||
                        (installationId != null && image.isOwnedByInstallation(installationId)))
            }
            .map { it.path }
            .toSet()
        return ownedPaths.containsAll(paths)
    }
}
