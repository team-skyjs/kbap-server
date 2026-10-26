package com.kbap.common.domain.order

import com.kbap.common.domain.order.model.Order
import org.springframework.data.domain.Limit
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface OrderJpaRepository : JpaRepository<Order, Long> {
    @Query(value = """
        SELECT photos.path AS path, MAX(photos.created_at) AS createdAt FROM (
            SELECT image_path AS path, created_at FROM orders WHERE member_id = :memberId AND status = 'ACTIVE' AND image_path IS NOT NULL AND image_path <> ''
            UNION ALL
            SELECT p.object_key AS path, p.created_at FROM scan_operation_photo p
            JOIN scan_operation o ON o.id = p.operation_id WHERE o.member_id = :memberId AND o.status = 'ACTIVE' AND p.status = 'ACTIVE'
        ) photos GROUP BY photos.path ORDER BY createdAt DESC, photos.path
    """, countQuery = """
        SELECT COUNT(*) FROM (
            SELECT image_path AS path FROM orders WHERE member_id = :memberId AND status = 'ACTIVE' AND image_path IS NOT NULL AND image_path <> ''
            UNION
            SELECT p.object_key AS path FROM scan_operation_photo p
            JOIN scan_operation o ON o.id = p.operation_id WHERE o.member_id = :memberId AND o.status = 'ACTIVE' AND p.status = 'ACTIVE'
        ) photos
    """, nativeQuery = true)
    fun findMemberScanPhotos(@Param("memberId") memberId: Long, pageable: Pageable): Page<MemberScanPhoto>

    @Query(nativeQuery = true, value = """
        SELECT * FROM (
            (SELECT 'ORDER' AS kind, o.id AS rowId, o.id AS sourceId, o.image_path AS path, o.created_at AS createdAt, o.member_id AS memberId,
                m.nickname AS nickname, m.country_code AS countryCode, m.is_bot AS bot,
                CASE WHEN m.status = 'DELETED' THEN 'DELETED' ELSE m.member_status END AS memberStatus
            FROM orders o JOIN member m ON m.id = o.member_id
            WHERE o.status = 'ACTIVE' AND o.image_path IS NOT NULL AND o.image_path <> ''
              AND NOT EXISTS (SELECT 1 FROM order_photo op WHERE op.order_id = o.id)
              AND m.is_bot = :isBot AND (:memberId IS NULL OR o.member_id = :memberId)
            ORDER BY o.created_at DESC, o.id DESC LIMIT :limit)
            UNION ALL
            (SELECT 'BATCH' AS kind, p.id AS rowId, p.operation_id AS sourceId, p.object_key AS path, p.created_at AS createdAt, so.member_id AS memberId,
                m.nickname AS nickname, m.country_code AS countryCode, m.is_bot AS bot,
                CASE WHEN m.status = 'DELETED' THEN 'DELETED' ELSE m.member_status END AS memberStatus
            FROM scan_operation_photo p JOIN scan_operation so ON so.id = p.operation_id JOIN member m ON m.id = so.member_id
            WHERE p.status = 'ACTIVE' AND so.status = 'ACTIVE'
              AND m.is_bot = :isBot AND (:memberId IS NULL OR so.member_id = :memberId)
            ORDER BY p.created_at DESC, p.id DESC LIMIT :limit)
        ) feed ORDER BY createdAt DESC, kind DESC, rowId DESC LIMIT :limit
    """)
    fun findScanPhotoFeed(@Param("isBot") isBot: Boolean, @Param("memberId") memberId: Long?, @Param("limit") limit: Int): List<ScanPhotoFeedRow>

    @Query(nativeQuery = true, value = """
        SELECT * FROM (
            (SELECT 'ORDER' AS kind, o.id AS rowId, o.id AS sourceId, o.image_path AS path, o.created_at AS createdAt, o.member_id AS memberId,
                m.nickname AS nickname, m.country_code AS countryCode, m.is_bot AS bot,
                CASE WHEN m.status = 'DELETED' THEN 'DELETED' ELSE m.member_status END AS memberStatus
            FROM orders o JOIN member m ON m.id = o.member_id
            WHERE o.status = 'ACTIVE' AND o.image_path IS NOT NULL AND o.image_path <> ''
              AND NOT EXISTS (SELECT 1 FROM order_photo op WHERE op.order_id = o.id)
              AND m.is_bot = :isBot AND (:memberId IS NULL OR o.member_id = :memberId)
              AND (o.created_at < :cursorAt OR (o.created_at = :cursorAt AND :cursorKind = 'ORDER' AND o.id < :cursorId))
            ORDER BY o.created_at DESC, o.id DESC LIMIT :limit)
            UNION ALL
            (SELECT 'BATCH' AS kind, p.id AS rowId, p.operation_id AS sourceId, p.object_key AS path, p.created_at AS createdAt, so.member_id AS memberId,
                m.nickname AS nickname, m.country_code AS countryCode, m.is_bot AS bot,
                CASE WHEN m.status = 'DELETED' THEN 'DELETED' ELSE m.member_status END AS memberStatus
            FROM scan_operation_photo p JOIN scan_operation so ON so.id = p.operation_id JOIN member m ON m.id = so.member_id
            WHERE p.status = 'ACTIVE' AND so.status = 'ACTIVE'
              AND m.is_bot = :isBot AND (:memberId IS NULL OR so.member_id = :memberId)
              AND (p.created_at < :cursorAt OR (p.created_at = :cursorAt AND (:cursorKind = 'ORDER' OR p.id < :cursorId)))
            ORDER BY p.created_at DESC, p.id DESC LIMIT :limit)
        ) feed ORDER BY createdAt DESC, kind DESC, rowId DESC LIMIT :limit
    """)
    fun findScanPhotoFeedAfter(
        @Param("isBot") isBot: Boolean,
        @Param("memberId") memberId: Long?,
        @Param("cursorAt") cursorAt: LocalDateTime,
        @Param("cursorKind") cursorKind: String,
        @Param("cursorId") cursorId: Long,
        @Param("limit") limit: Int,
    ): List<ScanPhotoFeedRow>

    fun existsByImagePath(imagePath: String): Boolean

    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            UPDATE orders SET latitude = NULL, longitude = NULL, road_address = NULL,
                place_source = NULL, place_external_id = NULL, place_name = NULL, place_address = NULL, place_language = NULL
            WHERE member_id = :memberId
        """,
    )
    fun eraseLocationByMemberId(@Param("memberId") memberId: Long): Int

    fun findByMemberId(memberId: Long, pageable: Pageable): Page<Order>

    fun countByMemberId(memberId: Long): Long

    @Query(
        """
        select o from Order o
        where o.memberId = :memberId
          and (:cursor is null or o.id < :cursor)
        order by o.id desc
        """,
    )
    fun findPageByMemberId(
        @Param("memberId") memberId: Long,
        @Param("cursor") cursor: Long?,
        pageable: Pageable,
    ): List<Order>

    @Query(
        """
        select o from Order o
        where o.createdAt between :from and :to
          and o.id > :afterId
          and not exists (
            select 1 from Notification n
            where n.memberId = o.memberId
              and n.type = com.kbap.common.domain.notification.model.NotificationType.REVIEW_REMINDER
              and n.createdAt >= o.createdAt
          )
          and exists (
            select 1 from OrderItem oi
            where oi.orderId = o.id
              and not exists (
                select 1 from Review r
                where r.memberId = o.memberId and r.foodId = oi.foodId
              )
          )
        order by o.id
        """,
    )
    fun findReviewReminderTargets(
        @Param("from") from: LocalDateTime,
        @Param("to") to: LocalDateTime,
        @Param("afterId") afterId: Long,
        limit: Limit,
    ): List<Order>
}

interface ScanPhotoFeedRow {
    val kind: String
    val rowId: Long
    val sourceId: Long
    val path: String
    val createdAt: LocalDateTime
    val memberId: Long
    val nickname: String?
    val countryCode: String?
    val bot: Number
    val memberStatus: String
}

interface MemberScanPhoto {
    val path: String
    val createdAt: LocalDateTime
}
