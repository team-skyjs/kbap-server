package com.kbap.common.domain.chat

import com.kbap.common.domain.chat.model.ChatAuthorRole
import com.kbap.common.domain.chat.model.ChatMessage
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ChatMessageJpaRepository : JpaRepository<ChatMessage, Long> {
    @Query(
        "select m from ChatMessage m where m.conversationId = :conversationId and (:before is null or m.id < :before) order by m.id desc",
    )
    fun findPage(@Param("conversationId") conversationId: Long, @Param("before") before: Long?, pageable: Pageable): List<ChatMessage>

    fun countByConversationIdAndAuthorRoleAndIdGreaterThan(conversationId: Long, authorRole: ChatAuthorRole, id: Long): Long

    fun findFirstByConversationIdAndAuthorRoleOrderByIdDesc(conversationId: Long, authorRole: ChatAuthorRole): ChatMessage?

    fun findFirstByConversationIdOrderByIdDesc(conversationId: Long): ChatMessage?

    @Query("select m from ChatMessage m where m.id in (select max(x.id) from ChatMessage x where x.conversationId in :conversationIds group by x.conversationId)")
    fun findLatestByConversationIds(@Param("conversationIds") conversationIds: Collection<Long>): List<ChatMessage>

    fun existsByLegacyFeedbackIdAndLegacyReplyIdIsNull(legacyFeedbackId: Long): Boolean

    fun findFirstByLegacyFeedbackIdOrderByIdAsc(legacyFeedbackId: Long): ChatMessage?

    @Query("select m.createdAt from ChatMessage m where m.conversationId = :conversationId and m.authorRole = com.kbap.common.domain.chat.model.ChatAuthorRole.USER and m.createdAt >= :since")
    fun findUserMessageTimesSince(@Param("conversationId") conversationId: Long, @Param("since") since: java.time.LocalDateTime): List<java.time.LocalDateTime>
}
