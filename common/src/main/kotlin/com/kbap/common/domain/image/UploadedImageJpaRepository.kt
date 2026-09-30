package com.kbap.common.domain.image

import com.kbap.common.domain.image.model.UploadedImage
import org.springframework.data.jpa.repository.JpaRepository

interface UploadedImageJpaRepository : JpaRepository<UploadedImage, Long> {
    fun countByInstallationIdAndCreatedAtAfter(installationId: String, createdAt: java.time.LocalDateTime): Long

    @org.springframework.data.jpa.repository.Query("select e.createdAt from UploadedImage e where e.installationId = :installationId and e.createdAt > :since")
    fun findCreatedAtsByInstallationIdSince(
        @org.springframework.data.repository.query.Param("installationId") installationId: String,
        @org.springframework.data.repository.query.Param("since") since: java.time.LocalDateTime,
    ): List<java.time.LocalDateTime>

    fun findByPath(path: String): UploadedImage?

    fun findByPathIn(paths: Collection<String>): List<UploadedImage>
}
