package com.kbap.common.domain.scan

import com.kbap.common.domain.scan.model.ScanUsage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface ScanUsageJpaRepository : JpaRepository<ScanUsage, Long> {
    @Query(nativeQuery = true, value = """
        SELECT m.country_code AS countryCode,
            SUM(CASE WHEN x.scan_type = 'MENU' THEN 1 ELSE 0 END) AS menuCount,
            SUM(CASE WHEN x.scan_type = 'PACKAGED' THEN 1 ELSE 0 END) AS packagedCount
        FROM (
            SELECT o.member_id, CASE WHEN o.key_namespace = 'SCAN_BATCH' THEN 'MENU' ELSE 'PACKAGED' END AS scan_type
            FROM scan_usage s JOIN scan_operation o ON o.id = s.operation_id
            WHERE s.status = 'ACTIVE' AND s.recorded_at >= :since AND o.key_namespace IN ('SCAN_BATCH', 'PFOOD_NAME', 'PFOOD_LABEL')
            UNION ALL
            SELECT u.member_id, 'MENU' AS scan_type FROM uploaded_image u
            WHERE u.created_at >= :since AND locate(:menuSegment, u.object_path) > 0
              AND NOT EXISTS (SELECT 1 FROM scan_operation_photo p WHERE p.uploaded_image_id = u.id)
        ) x JOIN member m ON m.id = x.member_id
        WHERE m.is_bot = 0 AND x.member_id NOT IN (:excludedMemberIds)
        GROUP BY m.country_code
    """)
    fun countScansByCountrySince(
        @Param("since") since: LocalDateTime,
        @Param("menuSegment") menuSegment: String,
        @Param("excludedMemberIds") excludedMemberIds: Collection<Long>,
    ): List<ScanCountryCount>
}

interface ScanCountryCount {
    val countryCode: String?
    val menuCount: Long
    val packagedCount: Long
}
