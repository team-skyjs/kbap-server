package com.kbap.api.processedfood

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "가공식품 유형 칩 — 식품유형(food_type) 원문을 두 글자 이상 키워드(포함) 또는 유형명 전체(일치)로 묶는다. 어디에도 안 맞으면 null(전체에만 나온다)")
enum class ProcessedFoodType(private val contains: List<String>, private val names: Set<String> = emptySet()) {
    ICE_CREAM(listOf("아이스크림", "빙과", "아이스밀크", "샤베트", "빙수")),
    DAIRY(
        listOf("우유", "가공유", "발효유", "유음료", "요구르트", "치즈", "가공버터", "유산균", "분유", "조제유", "조제식", "유크림", "유제품", "산양유", "영유아식", "영·유아식"),
        setOf("버터"),
    ),
    RAMEN(listOf("유탕면", "국수", "냉면", "숙면", "건면", "생면", "파스타", "당면", "라면", "우동", "면류")),
    BREAD(listOf("빵류", "케이크", "도넛", "카스텔라", "베이커리"), setOf("빵", "기타빵")),
    SNACKS(
        listOf("과자", "캔디", "사탕", "초콜릿", "츄잉껌", "추잉껌", "견과", "조미김", "조미구이김", "김자반", "가공김", "시리얼", "유탕처리", "강정", "젤리", "양갱", "팝콘", "스낵"),
        setOf("김"),
    ),
    DRINKS(listOf("음료", "주스", "커피", "두유", "탄산수", "먹는샘물", "식혜", "수정과", "침출차", "액상차", "고형차", "다류", "홍차", "녹차")),
    MEALS(
        listOf(
            "즉석", "만두", "어묵", "김치", "김칫속", "프레스햄", "햄류", "소시지", "베이컨", "가공육", "포장육", "양념육", "저장육", "냉동육", "어육", "육가공", "식육함유",
            "갈비", "두부", "떡류", "떡볶이", "떡국", "순대", "수산물", "절임", "조림", "묵류", "젓갈", "도시락", "샐러드", "신선편의", "알가공", "알가열", "계란",
            "건어포", "건조포", "튀김", "카레",
        ),
        setOf("햄"),
    ),
    SAUCES(
        listOf(
            "소스", "드레싱", "고추장", "된장", "간장", "청국장", "춘장", "쌈장", "혼합장", "장류", "케첩", "마요네즈", "식초", "참기름", "들기름", "식용유", "기름", "유지",
            "땅콩버터", "조미", "향신료", "고춧가루", "소금", "천일염", "설탕", "당류", "과당", "포도당", "물엿", "엿류", "시럽", "양념", "분말", "가루", "전분", "추출가공", "육수",
            "올리브유", "해바라기유", "카놀라유", "향미유", "쇼트닝", "마가린", "올리고당", "액젓",
        ),
        setOf("잼", "잼류", "기타잼", "기타잼류", "엿", "기타엿", "꿀", "벌꿀"),
    ),
    ;

    companion object {
        private val NOISE = Regex("""[\s·.,()]""")

        fun of(foodType: String?): ProcessedFoodType? {
            val type = foodType?.replace(NOISE, "")?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { chip -> type in chip.names || chip.contains.any { it in type } }
        }
    }
}
