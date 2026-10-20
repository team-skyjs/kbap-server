package com.kbap.common.domain.curation.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.*
import java.time.LocalDateTime
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "curation_collection")
class CurationCollection(
    @Column(nullable = false) val releaseId: Long = 0,
    @Column(length = 20, nullable = false) val collectionKey: String = "",
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "json", nullable = false)
    var draftDocument: CurationDocument = CurationDocument(),
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "json")
    var publishedDocument: CurationDocument? = null,
    @Version var version: Long = 0,
    var publishedRevision: Long = 0,
    var publishedSourceVersion: Long? = null,
    @Column(name = "is_published") var isPublished: Boolean = false,
    var publishedAt: LocalDateTime? = null,
    var publishedBy: Long? = null,
    var updatedBy: Long? = null,
    var unpublishedAt: LocalDateTime? = null,
    var unpublishedBy: Long? = null,
) : BaseEntity() {
    fun updateDraft(document: CurationDocument, adminId: Long) {
        draftDocument = document
        updatedBy = adminId
    }
    fun publish(snapshot: CurationDocument, adminId: Long) {
        publishedDocument = snapshot
        publishedSourceVersion = version
        publishedRevision += 1
        isPublished = true
        publishedAt = LocalDateTime.now()
        publishedBy = adminId
    }
    fun unpublish(adminId: Long) {
        isPublished = false
        unpublishedAt = LocalDateTime.now()
        unpublishedBy = adminId
    }
}
