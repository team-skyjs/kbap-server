package com.kbap.common.domain.processedfood

import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface ProcessedFoodProductJpaRepository : JpaRepository<ProcessedFoodProduct, Long> {
    fun findAllByReportNoIn(reportNos: Collection<String>): List<ProcessedFoodProduct>

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

    @Query(
        nativeQuery = true,
        value = """
        SELECT * FROM processed_food_product
        WHERE status = 'ACTIVE' AND last_seen_at >= :seenSince
          AND normalized_name LIKE CONCAT('%', :escaped, '%') ESCAPE '\\'
        ORDER BY normalized_name = :keyword DESC, normalized_name LIKE CONCAT(:escaped, '%') ESCAPE '\\' DESC, CHAR_LENGTH(normalized_name), id
        LIMIT :limit
        """,
    )
    fun findNameCandidates(
        @Param("keyword") keyword: String,
        @Param("escaped") escaped: String,
        @Param("seenSince") seenSince: LocalDateTime,
        @Param("limit") limit: Int,
    ): List<ProcessedFoodProduct>

    @Query(
        nativeQuery = true,
        value = """
        SELECT * FROM processed_food_product
        WHERE status = 'ACTIVE' AND last_seen_at >= :seenSince
          AND (manufacturer LIKE CONCAT('%', :escaped, '%') ESCAPE '\\'
            OR seller LIKE CONCAT('%', :escaped, '%') ESCAPE '\\'
            OR maker_supplement LIKE CONCAT('%', :escaped, '%') ESCAPE '\\'
            OR REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(manufacturer, ' ', ''), '(', ''), ')', ''), '[', ''), ']', ''), '·', ''), '・', ''), ',', ''), '.', ''), '_', ''), '-', '') LIKE CONCAT('%', :escaped, '%') ESCAPE '\\'
            OR REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(seller, ' ', ''), '(', ''), ')', ''), '[', ''), ']', ''), '·', ''), '・', ''), ',', ''), '.', ''), '_', ''), '-', '') LIKE CONCAT('%', :escaped, '%') ESCAPE '\\'
            OR REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(maker_supplement, ' ', ''), '(', ''), ')', ''), '[', ''), ']', ''), '·', ''), '・', ''), ',', ''), '.', ''), '_', ''), '-', '') LIKE CONCAT('%', :escaped, '%') ESCAPE '\\')
        ORDER BY id
        LIMIT :limit
        """,
    )
    fun findMakerCandidates(
        @Param("escaped") escaped: String,
        @Param("seenSince") seenSince: LocalDateTime,
        @Param("limit") limit: Int,
    ): List<ProcessedFoodProduct>
}
