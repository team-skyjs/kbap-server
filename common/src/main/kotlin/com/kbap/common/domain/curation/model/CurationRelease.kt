package com.kbap.common.domain.curation.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "curation_release")
class CurationRelease(
    @Column(name = "region_key", length = 20, nullable = false)
    val regionKey: String = "SEOUL",
    var firstActivatedAt: LocalDateTime? = null,
    var version: Long = 0,
) : BaseEntity() {
    fun changePublication(allPublished: Boolean) {
        if (firstActivatedAt == null && allPublished) firstActivatedAt = LocalDateTime.now()
        version += 1
    }
}
