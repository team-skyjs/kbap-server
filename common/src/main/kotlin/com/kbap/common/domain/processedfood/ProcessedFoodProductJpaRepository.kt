package com.kbap.common.domain.processedfood

import com.kbap.common.domain.processedfood.dto.ProcessedFoodCandidateRow
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface ProcessedFoodProductJpaRepository : JpaRepository<ProcessedFoodProduct, Long> {
    fun findAllByReportNoIn(reportNos: Collection<String>): List<ProcessedFoodProduct>

    fun findByReportNo(reportNo: String): ProcessedFoodProduct?

    fun findAllByNormalizedNameIn(normalizedNames: Collection<String>): List<ProcessedFoodProduct>

    @Query(
        nativeQuery = true,
        value = """
        SELECT id, normalized_name AS normalizedName, manufacturer, seller, maker_supplement AS makerSupplement, last_seen_at AS lastSeenAt
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
}
