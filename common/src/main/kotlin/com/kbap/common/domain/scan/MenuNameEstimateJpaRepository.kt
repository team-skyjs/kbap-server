package com.kbap.common.domain.scan

import com.kbap.common.domain.scan.model.MenuNameEstimate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal

interface MenuNameEstimateJpaRepository : JpaRepository<MenuNameEstimate, Long> {
    fun findAllByNameKeyIn(nameKeys: Collection<String>): List<MenuNameEstimate>

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
}
