package com.kbap.common.domain.processedfood

import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

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
}
