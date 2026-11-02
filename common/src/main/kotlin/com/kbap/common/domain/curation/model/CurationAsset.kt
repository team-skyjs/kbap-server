package com.kbap.common.domain.curation.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "curation_asset")
class CurationAsset(
    @Column(nullable = false) val collectionId: Long = 0,
    @Column(nullable = false) val issuedByAdminId: Long = 0,
    @Column(length = 20, nullable = false) var assetState: String = "ISSUED",
    @Column(length = 512, nullable = false) val stagingKey: String = "",
    @Column(length = 512) var finalKey: String? = null,
    @Column(length = 100, nullable = false) val contentType: String = "",
    @Column(nullable = false) val sizeBytes: Long = 0,
    val expiresAt: LocalDateTime = LocalDateTime.MIN,
    val completeDeadline: LocalDateTime = LocalDateTime.MIN,
    var completedAt: LocalDateTime? = null,
    var unreferencedSince: LocalDateTime? = null,
    @Column(length = 255) var sourceFingerprint: String? = null,
    @Column(length = 512) var displayKey: String? = null,
) : BaseEntity() {
    fun complete(key: String, fingerprint: String, now: LocalDateTime) {
        assetState = "READY"
        finalKey = key
        sourceFingerprint = fingerprint
        completedAt = now
        unreferencedSince = now
    }

    fun attachDisplayVariant(key: String) {
        displayKey = key
    }
}
