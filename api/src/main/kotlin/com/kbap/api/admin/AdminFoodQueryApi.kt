package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 음식 탐색", description = "음식 목록(검색·필터·정렬·삭제 포함)·상세(파이프라인 이력 동봉)·재료 카탈로그")
@SecurityRequirement(name = "bearerAuth")
interface AdminFoodQueryApi {
    @Operation(
        summary = "음식 목록",
        description = """
            - `q`: 숫자면 id 정확 일치, 아니면 표시명 포함 검색
            - `ingredient`: 재료 코드(IngredientCode) 포함 음식 · `translation`: 9개 언어 번역명 포함 검색
            - `includeDeleted=true` 면 소프트 삭제 행도 `deleted:true` 로 포함
            - `sort`: `id,desc`(기본) · `updatedAt,asc|desc` · `displayName,asc|desc`
            - 각 항목에 `reviewCount`·`vectorSyncStatus`(PENDING|COMPLETE|FAILED|NONE)·`hasImage` 동봉
        """,
    )
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회 성공"), ApiResponse(responseCode = "400", description = "size>200·page<1·sort 형식 오류(COMMON-002)"), ApiResponse(responseCode = "403", description = "AUTH-008")])
    fun getFoods(
        @Parameter(description = "id 또는 표시명 검색어") q: String?,
        @Parameter(description = "재료 코드", example = "CHICKEN") ingredient: String?,
        @Parameter(description = "번역명 검색어") translation: String?,
        status: FoodContentStatus?,
        failureKind: FoodContentFailureKind?,
        @Parameter(description = "삭제 포함") includeDeleted: Boolean,
        @Parameter(description = "정렬", example = "updatedAt,asc") sort: String,
        @Parameter(example = "1") page: Int,
        @Parameter(description = "최대 200", example = "50") size: Int,
    ): ResponseEntity<BaseResponse<AdminFoodListResponse>>

    @Operation(
        summary = "음식 상세",
        description = "삭제된 음식도 조회된다(`deleted:true`). `history` 에 콘텐츠 수집 요청 최근 10건·이미지 배치 아이템 10건·벡터 동기화 5건·리뷰 요약·스캔 매칭 수·북마크 수·감사 이력 10건을 동봉한다. `allowedTransitions` 로 가능한 전이를 안다.",
    )
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회 성공"), ApiResponse(responseCode = "400", description = "없는 음식(FOOD-001)")])
    fun getFood(@Parameter(description = "음식 id") id: Long): ResponseEntity<BaseResponse<AdminFoodDetailResponse>>

    @Operation(summary = "재료 카탈로그", description = "재료 편집기 선택지 — 코드·한국어명·9개 언어 번역·이미지(코드순)")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회 성공")])
    fun getIngredients(): ResponseEntity<BaseResponse<AdminIngredientCatalogResponse>>
}
