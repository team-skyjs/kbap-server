package com.kbap.core.food

fun interface FoodImageGenerationClient {
    // storageKey 위치에 이미지 저장까지 완료한 뒤 그 키를 반환한다(절대 URL 금지 — CDN 키 관례).
    // 같은 키 재호출은 덮어쓰기로 멱등이다.
    fun call(koreanName: String, storageKey: String): String
}
