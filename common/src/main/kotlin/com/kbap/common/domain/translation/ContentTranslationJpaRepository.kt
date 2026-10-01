package com.kbap.common.domain.translation

import com.kbap.common.domain.translation.model.ContentTranslation
import com.kbap.common.domain.translation.model.TranslationTargetType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ContentTranslationJpaRepository : JpaRepository<ContentTranslation, Long> {
    fun findByTargetTypeAndTargetIdAndLanguage(targetType: TranslationTargetType, targetId: Long, language: String): ContentTranslation?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO content_translation (target_type, target_id, language, source_hash, translated_text, status, created_at, updated_at)
            VALUES (:targetType, :targetId, :language, :sourceHash, :translatedText, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE
                source_hash = VALUES(source_hash),
                translated_text = VALUES(translated_text),
                status = 'ACTIVE',
                updated_at = CURRENT_TIMESTAMP(6)
        """,
    )
    fun upsert(
        @Param("targetType") targetType: String,
        @Param("targetId") targetId: Long,
        @Param("language") language: String,
        @Param("sourceHash") sourceHash: String,
        @Param("translatedText") translatedText: String,
    ): Int
}
