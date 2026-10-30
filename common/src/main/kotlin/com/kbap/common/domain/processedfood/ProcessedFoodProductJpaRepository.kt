package com.kbap.common.domain.processedfood

import com.kbap.common.domain.processedfood.dto.ProcessedFoodCandidateRow
import com.kbap.common.domain.processedfood.model.CutoutReviewStatus
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.LocalDateTime

interface ProcessedFoodProductJpaRepository : JpaRepository<ProcessedFoodProduct, Long> {
    fun findAllByReportNoIn(reportNos: Collection<String>): List<ProcessedFoodProduct>

    fun findByReportNo(reportNo: String): ProcessedFoodProduct?

    fun findAllByNormalizedNameIn(normalizedNames: Collection<String>): List<ProcessedFoodProduct>

    @Query(
        nativeQuery = true,
        value = """
        SELECT id, normalized_name AS normalizedName, manufacturer, seller, maker_supplement AS makerSupplement, last_seen_at AS lastSeenAt, source, food_type AS foodType
        FROM processed_food_product WHERE status = 'ACTIVE'
        """,
    )
    fun findCandidateRows(): List<ProcessedFoodCandidateRow>

    @Query(
        nativeQuery = true,
        value = "SELECT CONCAT_WS('|', COUNT(*), SUM(status = 'ACTIVE'), MAX(id), MAX(updated_at), MAX(last_seen_at)) FROM processed_food_product",
    )
    fun findCandidateFingerprint(): String?

    @Query(
        nativeQuery = true,
        value = """
        SELECT * FROM processed_food_product
        WHERE status = 'ACTIVE' AND maker_checked_at IS NULL
          AND (manufacturer IS NULL OR TRIM(SUBSTRING_INDEX(manufacturer, '_', 1)) IN ('', '알수없음'))
          AND (seller IS NULL OR TRIM(SUBSTRING_INDEX(seller, '_', 1)) IN ('', '알수없음'))
        ORDER BY id
        LIMIT :limit
        """,
    )
    fun findMakerLookupTargets(@Param("limit") limit: Int): List<ProcessedFoodProduct>

    @Query(
        nativeQuery = true,
        value = """
        SELECT MAX(e.START_TIME) FROM BATCH_JOB_EXECUTION e
        JOIN BATCH_JOB_INSTANCE i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID
        WHERE i.JOB_NAME = 'processedFoodMirrorJob' AND e.STATUS = 'COMPLETED' AND e.EXIT_CODE = 'COMPLETED'
        """,
    )
    fun findLastCompletedMirrorStartedAt(): LocalDateTime?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ProcessedFoodProduct p WHERE p.id IN :ids ORDER BY p.id")
    fun findAllForUpdateByIdIn(@Param("ids") ids: Collection<Long>): List<ProcessedFoodProduct>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ProcessedFoodProduct p WHERE p.id = :id")
    fun findForUpdateById(@Param("id") id: Long): ProcessedFoodProduct?

    @Query(
        """
        SELECT p FROM ProcessedFoodProduct p
        WHERE p.cutoutReviewStatus IS NOT NULL
          AND (:status IS NULL OR p.cutoutReviewStatus = :status)
          AND (:route IS NULL OR p.cutoutRoute = :route)
          AND (:minCoverage IS NULL OR p.cutoutCoverage >= :minCoverage)
          AND (:maxCoverage IS NULL OR p.cutoutCoverage <= :maxCoverage)
          AND (:cursor IS NULL OR p.id > :cursor)
        ORDER BY p.id
        """,
    )
    fun findCutoutReviewPage(
        @Param("status") status: CutoutReviewStatus?,
        @Param("route") route: String?,
        @Param("minCoverage") minCoverage: BigDecimal?,
        @Param("maxCoverage") maxCoverage: BigDecimal?,
        @Param("cursor") cursor: Long?,
        pageable: Pageable,
    ): List<ProcessedFoodProduct>

    @Query(
        """
        SELECT COUNT(p) FROM ProcessedFoodProduct p
        WHERE p.cutoutReviewStatus = :pending AND p.cutoutRoute = :route AND p.cutoutCoverage >= :minCoverage
        """,
    )
    fun countCutoutsToApprove(
        @Param("pending") pending: CutoutReviewStatus,
        @Param("route") route: String,
        @Param("minCoverage") minCoverage: BigDecimal,
    ): Long

    @Modifying(flushAutomatically = true)
    @Query(
        """
        UPDATE ProcessedFoodProduct p
        SET p.cutoutReviewStatus = :approved, p.cutoutReviewedAt = :now, p.cutoutReviewedBy = :adminId,
            p.cutoutReviewReason = NULL, p.updatedAt = :now
        WHERE p.cutoutReviewStatus = :pending AND p.cutoutRoute = :route AND p.cutoutCoverage >= :minCoverage
        """,
    )
    fun approveCutouts(
        @Param("pending") pending: CutoutReviewStatus,
        @Param("approved") approved: CutoutReviewStatus,
        @Param("route") route: String,
        @Param("minCoverage") minCoverage: BigDecimal,
        @Param("adminId") adminId: Long,
        @Param("now") now: LocalDateTime,
    ): Int
}
