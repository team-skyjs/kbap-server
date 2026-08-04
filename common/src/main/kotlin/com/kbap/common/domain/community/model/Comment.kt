package com.kbap.common.domain.community.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "community_comment")
class Comment(
    @Column(name = "post_id", nullable = false)
    val postId: Long,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(nullable = false, length = MAX_CONTENT_LENGTH)
    var content: String,

    // 대댓글이면 항상 최상위 댓글 id — 1depth 불변식은 저장 전 서비스 정규화가 보장
    @Column(name = "parent_id")
    val parentId: Long? = null,
) : BaseEntity() {
    // 사용자 수정만 기록한다 — updatedAt 은 소프트 삭제 등 모든 변경에 움직여 판별에 못 쓴다.
    @Column(name = "edited_at")
    var editedAt: LocalDateTime? = null

    val isReply: Boolean
        get() = parentId != null

    init {
        requireValid(content)
    }

    fun update(content: String) {
        requireValid(content)
        this.content = content
        this.editedAt = LocalDateTime.now()
    }

    fun isOwnedBy(memberId: Long): Boolean = this.memberId == memberId

    private fun requireValid(content: String) {
        require(content.isNotBlank()) { "본문은 비어 있을 수 없습니다" }
        require(content.length <= MAX_CONTENT_LENGTH) { "본문은 최대 ${MAX_CONTENT_LENGTH}자입니다" }
    }

    companion object {
        const val MAX_CONTENT_LENGTH = 2000
    }
}
