package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 가공식품 번역", description = "관리자 전용 — 대표 상품 미리 번역")
@SecurityRequirement(name = "bearerAuth")
interface AdminProcessedFoodTranslationApi {
    @Operation(
        summary = "대표 상품 미리 번역 시작",
        description = """
            대표 상품 카탈로그(약 100개)의 제품명·제조사(브랜드 사전에 없을 때)·원재료·알레르기 표시를 언어마다 번역해 저장한다.
            뒤에서 돌고 바로 202 를 준다 — 진행·결과는 서버 로그('가공식품 대표 상품 미리 번역 완료'). 이미 저장된 번역은 다시 부르지 않는다.
            일일 상한(kbap.processed-food.translation.daily-budget-krw)에 닿으면 남은 항목은 건너뛴다.
            - **ADMIN 역할 JWT 전용**.
        """,
    )
    fun prewarm(
        @Parameter(description = "언어 코드 목록(쉼표) — 없으면 ko 를 뺀 9개 전부", example = "en,ja") languages: String?,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodPrewarmResponse>>
}
