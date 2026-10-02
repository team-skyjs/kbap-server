package com.kbap.api.translation

import com.kbap.common.domain.translation.model.TranslationTargetType
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive

@Schema(description = "번역 요청 — 대상 종류 + id")
data class TranslationRequest(
    @field:NotNull(message = "targetType 은 필수입니다")
    @field:Schema(description = "번역할 글의 종류. 지금은 REVIEW(리뷰 본문)만", example = "REVIEW")
    val targetType: TranslationTargetType?,

    @field:NotNull(message = "targetId 는 필수입니다")
    @field:Positive(message = "targetId 는 1 이상이어야 합니다")
    @field:Schema(description = "대상 id — REVIEW 면 리뷰 id", example = "53")
    val targetId: Long?,
)

@Schema(description = "번역 결과")
data class TranslationResponse(
    @field:Schema(example = "REVIEW")
    val targetType: String,
    val targetId: Long,
    @field:Schema(description = "번역된 언어 코드 — 요청 lang 이 미지원 값이면 en 으로 풀린 값", example = "ko")
    val language: String,
    @field:Schema(description = "번역문. 본문이 빈 글이면 빈 문자열", example = "국물이 깊고 정말 맛있었어요")
    val text: String,
    val sourceLanguage: String?,
)
