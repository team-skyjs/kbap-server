package com.kbap.api.processedfood

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "가공식품 유형 칩 — 식품유형(food_type) 원문을 키워드로 묶는다. 어디에도 안 맞으면 null(전체에만 나온다)")
enum class ProcessedFoodType(private val keywords: List<String>) {
    ICE_CREAM(listOf("아이스크림", "빙과", "아이스밀크", "샤베트", "빙수")),
    DAIRY(listOf("우유", "가공유", "발효유", "유음료", "치즈", "버터", "유산균", "분유", "조제유", "조제식")),
    RAMEN(listOf("유탕면", "국수", "냉면", "숙면", "건면", "생면", "파스타", "당면", "라면", "우동", "면류")),
    BREAD(listOf("빵", "케이크", "도넛", "베이커리")),
    SNACKS(listOf("과자", "캔디", "사탕", "초콜릿", "껌", "견과", "조미김", "가공김", "시리얼", "유탕처리", "강정", "젤리", "양갱", "팝콘", "스낵")),
    DRINKS(listOf("음료", "주스", "커피", "두유", "탄산수", "먹는샘물", "식혜", "수정과", "차")),
    MEALS(listOf("즉석", "만두", "어묵", "김치", "햄", "소시지", "베이컨", "육", "갈비", "두부", "떡", "죽", "수산물", "절임", "조림", "묵", "젓갈", "밥", "도시락", "샐러드", "신선편의", "알가공", "알가열", "계란", "건어", "포", "김", "반찬", "카레")),
    SAUCES(listOf("소스", "드레싱", "장", "케첩", "마요네즈", "식초", "기름", "유지", "잼", "조미", "향신료", "고춧가루", "소금", "설탕", "당류", "엿", "꿀", "시럽", "양념", "분말", "가루", "전분")),
    ;

    companion object {
        private val NOISE = Regex("""[\s·.,()]""")

        fun of(foodType: String?): ProcessedFoodType? {
            val type = foodType?.replace(NOISE, "")?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { chip -> chip.keywords.any { it in type } }
        }
    }
}
