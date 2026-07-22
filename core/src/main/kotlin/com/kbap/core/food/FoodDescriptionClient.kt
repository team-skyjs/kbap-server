package com.kbap.core.food

fun interface FoodDescriptionClient {
    fun call(koreanName: String): FoodDescriptionContent
}

data class FoodDescriptionContent(
    val description: String,
    val translations: TargetLanguageTexts,
    val spiciness: Int,
) {
    init {
        require(description.isNotBlank()) { "description 은 blank 일 수 없습니다" }
        require(description.length <= MAX_DESCRIPTION_LENGTH) {
            "description 은 ${MAX_DESCRIPTION_LENGTH}자를 넘을 수 없습니다: ${description.length}"
        }
        require(description != PLACEHOLDER_DESCRIPTION) { "description 은 플레이스홀더일 수 없습니다" }
        require(spiciness in SPICINESS_RANGE) { "spiciness 는 $SPICINESS_RANGE 여야 합니다: $spiciness" }
    }

    companion object {
        // food.description 컬럼 길이·플레이스홀더와 정합(:domain:food 는 :core 가 참조 불가라 값을 복제).
        const val MAX_DESCRIPTION_LENGTH = 255
        const val PLACEHOLDER_DESCRIPTION = "설명 준비 중"
        // 회원 맵기 선호(0..10)와 같은 스케일 — 센티널 -1 은 미설정 표현이라 생성 결과엔 올 수 없다.
        val SPICINESS_RANGE = 0..10
    }
}
