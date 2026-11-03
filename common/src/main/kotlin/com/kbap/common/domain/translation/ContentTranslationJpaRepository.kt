package com.kbap.common.domain.translation

import com.kbap.common.domain.translation.model.ContentTranslation
import com.kbap.common.domain.translation.model.TranslationTargetType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface ContentTranslationJpaRepository : JpaRepository<ContentTranslation, Long> {
    fun findByTargetTypeAndTargetIdAndLanguage(targetType: TranslationTargetType, targetId: Long, language: String): ContentTranslation?

    fun findAllByTargetTypeInAndTargetIdInAndLanguage(targetTypes: Collection<TranslationTargetType>, targetIds: Collection<Long>, language: String): List<ContentTranslation>

    fun findAllByTargetTypeInAndTargetIdIn(targetTypes: Collection<TranslationTargetType>, targetIds: Collection<Long>): List<ContentTranslation>

    @Query(
        nativeQuery = true,
        value = "SELECT * FROM content_translation WHERE target_type = :targetType AND status = 'ACTIVE' AND id > :afterId ORDER BY id LIMIT :limit",
    )
    fun findPageByTargetType(@Param("targetType") targetType: String, @Param("afterId") afterId: Long, @Param("limit") limit: Int): List<ContentTranslation>

    fun countByTargetType(targetType: TranslationTargetType): Long

    @Query(
        nativeQuery = true,
        value = """
        SELECT target_id AS targetId, target_type AS targetType, language, source_hash AS sourceHash, translated_text AS translatedText
        FROM content_translation WHERE target_type IN ('PF_NAME', 'PF_NAME_PINNED') AND status = 'ACTIVE'
        """,
    )
    fun findProcessedFoodNameRows(): List<ProcessedFoodNameTranslationRow>

    @Query(
        nativeQuery = true,
        value = "SELECT CONCAT_WS('|', COUNT(*), SUM(status = 'ACTIVE'), MAX(id), MAX(updated_at)) FROM content_translation WHERE target_type IN ('PF_NAME', 'PF_NAME_PINNED')",
    )
    fun findProcessedFoodNameFingerprint(): String?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO content_translation (target_type, target_id, language, source_hash, translated_text, source_language, status, created_at, updated_at)
            VALUES (:targetType, :targetId, :language, :sourceHash, :translatedText, :sourceLanguage, 'ACTIVE', :now, :now)
            ON DUPLICATE KEY UPDATE
                source_hash = :sourceHash,
                translated_text = :translatedText,
                source_language = :sourceLanguage,
                status = 'ACTIVE',
                updated_at = :now
        """,
    )
    fun upsert(
        @Param("targetType") targetType: String,
        @Param("targetId") targetId: Long,
        @Param("language") language: String,
        @Param("sourceHash") sourceHash: String,
        @Param("translatedText") translatedText: String,
        @Param("sourceLanguage") sourceLanguage: String?,
        @Param("now") now: LocalDateTime,
    ): Int
}
