package com.kbap.api.review

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "리뷰 대상 필터 — ALL(기본): 음식·가공식품 모두 · FOOD: 음식 리뷰만 · PROCESSED_FOOD: 가공식품 리뷰만(응답 targetType 과 같은 값)")
enum class ReviewTargetFilter { ALL, FOOD, PROCESSED_FOOD }
