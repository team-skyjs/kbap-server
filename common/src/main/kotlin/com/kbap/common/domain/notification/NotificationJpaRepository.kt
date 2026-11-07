package com.kbap.common.domain.notification

import com.kbap.common.domain.notification.model.Notification
import com.kbap.common.domain.notification.model.NotificationType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface NotificationJpaRepository : JpaRepository<Notification, Long> {
    @Query(
        """
        select n from Notification n
        where n.memberId = :memberId
          and (:cursor is null or n.id < :cursor)
        order by n.id desc
        """,
    )
    fun findPageByMemberId(
        @Param("memberId") memberId: Long,
        @Param("cursor") cursor: Long?,
        pageable: Pageable,
    ): List<Notification>

    fun findByMemberIdAndInstallationIdAndCreatedAtAfterOrderByIdDesc(
        memberId: Long,
        installationId: String,
        since: LocalDateTime,
    ): List<Notification>

    fun findByIdAndMemberIdAndInstallationId(id: Long, memberId: Long, installationId: String): Notification?

    fun countByMemberIdAndReadAtIsNull(memberId: Long): Long

    @Query(
        """
        select n.memberId as memberId, n.foodId as foodId from Notification n
        where n.memberId in :memberIds
          and n.type = :type
          and n.createdAt >= :since
          and n.foodId is not null
        """,
    )
    fun findFoodIdsSince(
        @Param("memberIds") memberIds: Collection<Long>,
        @Param("type") type: NotificationType,
        @Param("since") since: LocalDateTime,
    ): List<MemberFoodId>

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.memberId = :memberId and n.readAt is null")
    fun markAllReadByMemberId(@Param("memberId") memberId: Long, @Param("now") now: LocalDateTime): Int
}

interface MemberFoodId {
    val memberId: Long
    val foodId: Long
}
