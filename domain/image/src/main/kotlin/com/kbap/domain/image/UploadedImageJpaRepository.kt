package com.kbap.domain.image

import com.kbap.domain.image.model.UploadedImage
import org.springframework.data.jpa.repository.JpaRepository

interface UploadedImageJpaRepository : JpaRepository<UploadedImage, Long> {
    fun findByPath(path: String): UploadedImage?
}
