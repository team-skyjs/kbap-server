package com.kbap.common.domain.translation.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "content_translation",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_content_translation_target_language", columnNames = ["target_type", "target_id", "language"]),
    ],
)
class ContentTranslation(
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    val targetType: TranslationTargetType = TranslationTargetType.REVIEW,

    @Column(name = "target_id", nullable = false)
    val targetId: Long = 0,

    @Column(name = "language", nullable = false, length = 10)
    val language: String = "",

    @Column(name = "source_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    val sourceHash: String = "",

    @Column(name = "translated_text", nullable = false, columnDefinition = "text")
    val translatedText: String = "",

    @Column(name = "source_language", length = 35)
    val sourceLanguage: String? = null,
) : BaseEntity()
