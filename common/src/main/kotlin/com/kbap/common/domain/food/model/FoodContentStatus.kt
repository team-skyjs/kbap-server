package com.kbap.common.domain.food.model

enum class FoodContentStatus(val displayName: String) {
    FAILED("확인 필요"),
    PENDING_IMAGE("이미지 대기"),
    PENDING_REVIEW("승인 대기"),
    READY("준비 완료"),
}
