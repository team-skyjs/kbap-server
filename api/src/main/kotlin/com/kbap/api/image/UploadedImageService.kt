package com.kbap.api.image

import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadPurpose
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UploadedImageService(
    private val uploadedImageRepository: UploadedImageJpaRepository,
) {
    // 기본은 회원 소유 검증이고, installationId 를 주면 같은 기기 업로드까지 인정한다(문의 사진) —
    // 게스트로 올린 사진이 가입 후에도 통과한다.
    @Transactional(readOnly = true)
    fun ownsAllImages(
        memberId: Long?,
        paths: List<String>?,
        purpose: UploadPurpose,
        installationId: String? = null,
    ): Boolean {
        if (paths.isNullOrEmpty()) return true
        if (memberId == null && installationId == null) return false
        val segment = "images/${purpose.prefix}/"
        val ownedPaths = uploadedImageRepository.findByPathIn(paths)
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
