package com.kbap.common.domain.image

import com.kbap.common.domain.image.model.UploadedImage
import org.springframework.data.jpa.repository.JpaRepository

interface UploadedImageJpaRepository : JpaRepository<UploadedImage, Long> {
    fun findByPath(path: String): UploadedImage?
}
