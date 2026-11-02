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
        SELECT photos.path AS path, MAX(photos.created_at) AS createdAt, MAX(photos.scan_type) AS scanType,
            (SELECT pf.id FROM scan_operation so JOIN processed_food_product pf ON pf.report_no = JSON_UNQUOTE(JSON_EXTRACT(so.request_document, '$.selectedReportNo'))
                WHERE so.member_id = :memberId AND so.key_namespace = 'PFOOD_NAME' AND so.lifecycle = 'CONFIRMED'
                  AND JSON_UNQUOTE(JSON_EXTRACT(so.request_document, '$.imagePath')) = photos.path
                ORDER BY so.id DESC LIMIT 1) AS processedFoodId
        FROM ($MEMBER_SCAN_PHOTOS) photos GROUP BY photos.path ORDER BY createdAt DESC, photos.path
    """, countQuery = """
        SELECT COUNT(DISTINCT photos.path) FROM ($MEMBER_SCAN_PHOTOS) photos
    """, nativeQuery = true)
    fun findMemberScanPhotos(
        @Param("memberId") memberId: Long,
        @Param("includeMenu") includeMenu: Boolean,
        @Param("includePackaged") includePackaged: Boolean,
        @Param("packagedSegment") packagedSegment: String,
        pageable: Pageable,
    ): Page<MemberScanPhoto>

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

    companion object {
        const val MEMBER_SCAN_PHOTOS = """
            SELECT image_path AS path, created_at, 'MENU' AS scan_type FROM orders
            WHERE :includeMenu AND member_id = :memberId AND status = 'ACTIVE' AND image_path IS NOT NULL AND image_path <> ''
            UNION ALL
            SELECT p.object_key AS path, p.created_at, 'MENU' AS scan_type FROM scan_operation_photo p
            JOIN scan_operation o ON o.id = p.operation_id WHERE :includeMenu AND o.member_id = :memberId AND o.status = 'ACTIVE' AND p.status = 'ACTIVE'
            UNION ALL
            SELECT u.object_path AS path, u.created_at, 'PACKAGED' AS scan_type FROM uploaded_image u
            WHERE :includePackaged AND u.member_id = :memberId AND u.status = 'ACTIVE' AND locate(concat('/', :packagedSegment, '/'), concat('/', u.object_path)) > 0
        """
    }
}

interface MemberScanPhoto {
    val path: String
    val createdAt: LocalDateTime
    val scanType: String
    val processedFoodId: Long?
}
