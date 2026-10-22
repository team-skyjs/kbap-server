package com.kbap.api.processedfood

import org.springframework.web.bind.annotation.RequestParam
import com.kbap.api.core.ApiPaths
import com.kbap.api.core.ApiVersions
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.SearchPage
import com.kbap.api.core.auth.AuthMemberIdOrNull
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API + "/processed-foods")
class ProcessedFoodController(
    private val processedFoodService: ProcessedFoodService,
    private val translationService: ProcessedFoodTranslationService,
) : ProcessedFoodApi {
    @GetMapping(version = ApiVersions.PROCESSED_FOOD + "+")
    override fun search(
        @Valid @ModelAttribute request: ProcessedFoodSearchRequest,
        @AuthMemberIdOrNull memberId: Long?,
    ): ResponseEntity<BaseResponse<SearchPage<ProcessedFoodSummaryResponse>>> =
        processedFoodService.searchProcessedFoodPage(request.query, request.cursor, request.size, memberId, request.type)
            .let { page -> page.copy(items = translationService.localizeSummaries(page.items, ProcessedFoodTranslationService.languageOf(request.lang))) }
            .let { ResponseEntity.ok(BaseResponse.ok(it)) }

    @GetMapping("/{id}", version = ApiVersions.PROCESSED_FOOD + "+")
    override fun detail(
        @PathVariable id: Long,
        @AuthMemberIdOrNull memberId: Long?,
        @RequestParam(required = false) lang: String?,
    ): ResponseEntity<BaseResponse<ProcessedFoodDetailResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(translationService.localizeDetail(processedFoodService.getProcessedFoodDetail(id, memberId), ProcessedFoodTranslationService.languageOf(lang), member = memberId != null)),
        )
}

@Schema(description = "가공식품 검색 요청")
data class ProcessedFoodSearchRequest(
    @field:Size(max = 100, message = "query 는 100자 이하여야 합니다")
    @field:Schema(description = "검색어(선택, 100자 이하) — 제품명·제조사, 공백으로 여러 토큰(앞 5개만 쓴다). 없거나 공백이면 목록: 대표 상품(카탈로그 순) 먼저, 그다음 나머지(같은 제품 한 칸)", example = "농심 신라면")
    val query: String? = null,
    @field:Schema(description = "유형 칩 — 없으면 전체. 검색어가 있어도 결과를 이 유형으로 거른다", example = "SNACKS")
    val type: ProcessedFoodType? = null,
    @field:Schema(description = "표시 언어(ko·en·ja·zh-Hans·zh-Hant·vi·id·th·ru·es) — 없으면 ko. 항목 name·maker 에 저장된 번역을 쓴다(없으면 원문)", example = "en")
    val lang: String? = null,
    @field:Schema(description = "직전 페이지 nextCursor 그대로(불투명). 미지정 시 첫 페이지", example = "20")
    val cursor: String? = null,
    @field:Min(1, message = "size 는 1 이상이어야 합니다")
    @field:Max(50, message = "size 는 50 이하여야 합니다")
    @field:Schema(description = "페이지 크기(1~50, 기본 20)", example = "20")
    val size: Int = 20,
)
