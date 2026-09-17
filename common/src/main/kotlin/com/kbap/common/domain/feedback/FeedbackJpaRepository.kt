package com.kbap.common.domain.feedback

import com.kbap.common.domain.feedback.model.Feedback
import com.kbap.common.domain.feedback.model.FeedbackStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface FeedbackJpaRepository : JpaRepository<Feedback, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Feedback f where f.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Feedback?

    @Query(
        """
        select f from Feedback f
        where f.id = :id
          and (f.installationId = :installationId or (:memberId is not null and f.memberId = :memberId))
        """,
    )
    fun findMineById(
        @Param("id") id: Long,
        @Param("installationId") installationId: String,
        @Param("memberId") memberId: Long?,
    ): Feedback?

    @Query(
        """
        select f from Feedback f
        where (f.installationId = :installationId or (:memberId is not null and f.memberId = :memberId))
          and (:cursor is null or f.id < :cursor)
        order by f.id desc
        """,
    )
    fun findMinePage(
        @Param("installationId") installationId: String,
        @Param("memberId") memberId: Long?,
        @Param("cursor") cursor: Long?,
        pageable: Pageable,
    ): List<Feedback>

    fun findByFeedbackStatusOrderByIdDesc(feedbackStatus: FeedbackStatus, pageable: Pageable): Page<Feedback>

    fun findAllByOrderByIdDesc(pageable: Pageable): Page<Feedback>

    fun countByInstallationIdAndCreatedAtAfter(installationId: String, createdAt: LocalDateTime): Long
}
