package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "Admin · 가공식품 묶음", description = "같은 상품 묶음 규칙 점검")
@SecurityRequirement(name = "bearerAuth")
interface AdminProcessedFoodGroupingApi {
    @Operation(
        summary = "용량·용기 묶음 미리보기",
        description = "읽기 전용. 활성 가공식품을 옛 묶음 키와 새 키(용량·용기 낱말 제거 — 같은 제조사 안)로 나눈 묶음 수와, 새로 합쳐지는 묶음 표본을 준다. **ADMIN 역할 JWT 전용**.",
    )
    fun getGroupingPreview(@Parameter(description = "표본 묶음 수(1~200, 기본 30)", example = "30") sampleSize: Int): ResponseEntity<BaseResponse<AdminProcessedFoodGroupingPreview>>
}
