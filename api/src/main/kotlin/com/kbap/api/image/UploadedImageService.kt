package com.kbap.api.image

import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadPurpose
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UploadedImageService(
    private val uploadedImageRepository: UploadedImageJpaRepository,
) {
    @Transactional(readOnly = true)
    fun ownsAllImages(memberId: Long, paths: List<String>?, purpose: UploadPurpose): Boolean {
        if (paths.isNullOrEmpty()) return true
        val segment = "images/${purpose.prefix}/"
        val ownedPaths = uploadedImageRepository.findByPathIn(paths)
            .filter { it.isOwnedBy(memberId) && it.path.contains(segment) }
            .map { it.path }
            .toSet()
        return ownedPaths.containsAll(paths)
    }
}
