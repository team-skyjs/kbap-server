package com.kbap.common.domain.image

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
        // 키 앞에 환경 접두(prod/ 등)가 붙으므로 startsWith 가 아니라 contains 로 용도 구간을 본다.
        val segment = "images/${purpose.prefix}/"
        val ownedPaths = uploadedImageRepository.findByPathIn(paths)
            .filter { it.isOwnedBy(memberId) && it.path.contains(segment) }
            .map { it.path }
            .toSet()
        return ownedPaths.containsAll(paths)
    }
}
