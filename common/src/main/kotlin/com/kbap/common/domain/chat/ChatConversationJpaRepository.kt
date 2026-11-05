package com.kbap.common.domain.chat

import com.kbap.common.domain.chat.model.ChatConversation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ChatConversationJpaRepository : JpaRepository<ChatConversation, Long> {
    @Query(value = "SELECT * FROM chat_conversation WHERE member_id = :memberId AND status = 'ACTIVE' FOR UPDATE", nativeQuery = true)
    fun findByMemberIdForUpdate(@Param("memberId") memberId: Long): ChatConversation?

    @Query(value = "SELECT * FROM chat_conversation WHERE guest_installation_id = :installationId AND member_id IS NULL AND status = 'ACTIVE' FOR UPDATE", nativeQuery = true)
    fun findGuestForUpdate(@Param("installationId") installationId: String): ChatConversation?

    @Query(value = "SELECT * FROM chat_conversation WHERE id = :id AND status = 'ACTIVE' FOR UPDATE", nativeQuery = true)
    fun findByIdForUpdate(@Param("id") id: Long): ChatConversation?

    @Query(value = "SELECT id FROM chat_conversation WHERE member_id = :memberId AND status = 'ACTIVE'", nativeQuery = true)
    fun findIdByMemberId(@Param("memberId") memberId: Long): Long?

    @Query(value = "SELECT id FROM chat_conversation WHERE guest_installation_id = :installationId AND member_id IS NULL AND status = 'ACTIVE'", nativeQuery = true)
    fun findGuestId(@Param("installationId") installationId: String): Long?

    @Modifying
    @Query(value = "INSERT IGNORE INTO chat_conversation (member_id, status, created_at, updated_at) VALUES (:memberId, 'ACTIVE', NOW(6), NOW(6))", nativeQuery = true)
    fun insertIgnoreForMember(@Param("memberId") memberId: Long): Int

    @Modifying
    @Query(value = "INSERT IGNORE INTO chat_conversation (guest_installation_id, status, created_at, updated_at) VALUES (:installationId, 'ACTIVE', NOW(6), NOW(6))", nativeQuery = true)
    fun insertIgnoreForGuest(@Param("installationId") installationId: String): Int

    fun findByMemberId(memberId: Long): ChatConversation?

    fun findByGuestInstallationIdAndMemberIdIsNull(installationId: String): ChatConversation?

    @Query(
        value = "SELECT c.id AS conversationId, c.member_id AS memberId, c.guest_installation_id AS installationId, " +
            "(SELECT COUNT(*) FROM chat_message m WHERE m.conversation_id = c.id AND m.author_role = 'USER' AND m.status = 'ACTIVE' AND m.id > COALESCE(c.operator_last_read_message_id, 0)) AS unreadCount " +
            "FROM chat_conversation c WHERE c.status = 'ACTIVE' AND c.last_message_at IS NOT NULL " +
            "ORDER BY (SELECT COUNT(*) FROM chat_message m WHERE m.conversation_id = c.id AND m.author_role = 'USER' AND m.status = 'ACTIVE' AND m.id > COALESCE(c.operator_last_read_message_id, 0)) > 0 DESC, " +
            "c.last_message_at DESC, c.id DESC LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun findAdminRows(@Param("limit") limit: Int, @Param("offset") offset: Long): List<ChatConversationAdminRow>

    @Query(
        value = "SELECT COUNT(DISTINCT m.conversation_id) AS conversations, COUNT(*) AS messages FROM chat_message m " +
            "JOIN chat_conversation c ON c.id = m.conversation_id AND c.status = 'ACTIVE' " +
            "WHERE m.author_role = 'USER' AND m.status = 'ACTIVE' AND m.id > COALESCE(c.operator_last_read_message_id, 0)",
        nativeQuery = true,
    )
    fun countOperatorUnread(): ChatUnreadTotals
}

interface ChatConversationAdminRow {
    val conversationId: Long
    val memberId: Long?
    val installationId: String?
    val unreadCount: Long
}

interface ChatUnreadTotals {
    val conversations: Long
    val messages: Long
}
