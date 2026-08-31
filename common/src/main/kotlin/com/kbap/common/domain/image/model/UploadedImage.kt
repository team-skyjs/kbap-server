package com.kbap.common.domain.image.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

// object_path 는 도메인 없는 오브젝트 경로만 담는다(CDN 도메인은 서버 설정 — KB-138).
@Entity
@Table(name = "uploaded_image")
class UploadedImage(
    @Column(name = "member_id", nullable = false)
    var memberId: Long = 0,

    @Column(name = "object_path", nullable = false, length = 512)
    var path: String = "",

    @Column(name = "content_type", nullable = false, length = 100)
    var contentType: String = "",

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long = 0,
) : BaseEntity() {
    fun isOwnedBy(memberId: Long): Boolean = this.memberId == memberId
}
