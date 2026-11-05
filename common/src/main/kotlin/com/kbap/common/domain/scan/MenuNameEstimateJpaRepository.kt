package com.kbap.common.domain.scan

import com.kbap.common.domain.scan.model.MenuNameEstimate
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal

interface MenuNameEstimateJpaRepository : JpaRepository<MenuNameEstimate, Long> {
    fun findAllByNameKeyIn(nameKeys: Collection<String>): List<MenuNameEstimate>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from MenuNameEstimate e where e.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): MenuNameEstimate?

    @Query(
        nativeQuery = true,
        value = "SELECT e.id AS id, e.name_key AS nameKey, e.kind AS kind, e.english_name AS englishName, e.description AS description, " +
            "CAST(e.avoidance_codes AS CHAR) AS avoidanceCodes, e.spiciness AS spiciness, e.confidence AS confidence, e.model AS model, " +
            "e.prompt_version AS promptVersion, e.created_at AS createdAt, e.promoted_at AS promotedAt, e.promoted_by AS promotedBy, " +
            "f.id AS foodId, f.content_status AS foodContentStatus, f.image_ref AS foodImageRef, COALESCE(h.cnt, 0) AS scanCount " +
            "FROM menu_name_estimate e " +
            "LEFT JOIN food f ON f.korean_name = e.name_key COLLATE utf8mb4_0900_ai_ci AND f.status = 'ACTIVE' " +
            "LEFT JOIN (SELECT food_id, COUNT(*) AS cnt FROM scan_history WHERE status = 'ACTIVE' AND food_id IS NOT NULL GROUP BY food_id) h ON h.food_id = f.id " +
            "WHERE e.status = 'ACTIVE' $ADMIN_FILTER " +
            "ORDER BY CASE WHEN :sort = 'SCAN_COUNT' THEN COALESCE(h.cnt, 0) END DESC, CASE WHEN :sort = 'RECENT' THEN e.created_at END DESC, e.id DESC " +
            "LIMIT :limit OFFSET :offset",
    )
    fun findAdminRows(
        @Param("kind") kind: String?,
        @Param("promoted") promoted: Boolean?,
        @Param("foodStatus") foodStatus: String?,
        @Param("sort") sort: String,
        @Param("limit") limit: Int,
        @Param("offset") offset: Long,
    ): List<MenuNameEstimateAdminRow>

    @Query(
        nativeQuery = true,
        value = "SELECT COUNT(*) FROM menu_name_estimate e LEFT JOIN food f ON f.korean_name = e.name_key COLLATE utf8mb4_0900_ai_ci AND f.status = 'ACTIVE' " +
            "WHERE e.status = 'ACTIVE' $ADMIN_FILTER",
    )
    fun countAdminRows(
        @Param("kind") kind: String?,
        @Param("promoted") promoted: Boolean?,
        @Param("foodStatus") foodStatus: String?,
    ): Long

    @Modifying
    @Query(
        nativeQuery = true,
        value = "INSERT IGNORE INTO menu_name_estimate (name_key, kind, english_name, description, avoidance_codes, spiciness, confidence, model, prompt_version, status, created_at, updated_at) " +
            "VALUES (:nameKey, :kind, :englishName, :description, CAST(:avoidanceCodes AS JSON), :spiciness, :confidence, :model, :promptVersion, 'ACTIVE', NOW(6), NOW(6))",
    )
    fun insertIgnore(
        @Param("nameKey") nameKey: String,
        @Param("kind") kind: String,
        @Param("englishName") englishName: String?,
        @Param("description") description: String?,
        @Param("avoidanceCodes") avoidanceCodes: String,
        @Param("spiciness") spiciness: Int,
        @Param("confidence") confidence: BigDecimal?,
        @Param("model") model: String,
        @Param("promptVersion") promptVersion: String,
    ): Int

    @Modifying
    @Query(
        nativeQuery = true,
        value = "UPDATE menu_name_estimate SET image_ref = :imageRef, image_generated_at = NOW(6) WHERE name_key = :nameKey AND image_ref IS NULL",
    )
    fun attachImage(@Param("nameKey") nameKey: String, @Param("imageRef") imageRef: String): Int

    @Modifying
    @Query(nativeQuery = true, value = "DELETE FROM menu_name_estimate WHERE name_key = :nameKey")
    fun deleteByNameKey(@Param("nameKey") nameKey: String): Int

    companion object {
        const val ADMIN_FILTER =
            "AND (:kind IS NULL OR e.kind = :kind) " +
                "AND (:promoted IS NULL OR (e.promoted_at IS NOT NULL) = :promoted) " +
                "AND (:foodStatus IS NULL OR (:foodStatus = 'NONE' AND f.id IS NULL) OR f.content_status = :foodStatus)"
    }
}
