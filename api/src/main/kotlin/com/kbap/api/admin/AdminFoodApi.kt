package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 음식", description = "음식 목록(검색·필터·정렬·삭제 포함)·상세(파이프라인 이력 동봉)·재료 카탈로그·수정(낙관락·검증)·승인/반려·상태 전이·삭제/복구 — 모든 조작은 감사 이력에 남는다")
@SecurityRequirement(name = "bearerAuth")
interface AdminFoodApi {
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

    @Operation(
        summary = "음식 수정",
        description = """
            상세 조회 시 받은 `version` 을 함께 보내야 하며, 다르면 409(COMMON-004, payload.currentVersion).
            콘텐츠 검증(설명 1~255·맵기 0~10·번역 9개 언어·재료 카탈로그 코드·비율 1~100)에 실패하면 400(FOOD-006, payload.errors[]).
            `contentStatus` 는 받지 않는다 — 상태는 승인/반려/전이 API 로만 바뀐다. READY 음식을 고치면 벡터 동기화가 예약된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "수정 성공 — 상세 응답"),
            ApiResponse(responseCode = "400", description = "검증 실패(FOOD-006) 또는 미지 필드·필수값 누락(COMMON-002)", content = [Content(schema = Schema(implementation = BaseResponse::class))]),
            ApiResponse(responseCode = "409", description = "버전 충돌(COMMON-004) 또는 이름 중복(FOOD-007)"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun updateFood(
        @Parameter(description = "음식 id") id: Long,
        request: AdminFoodUpdateRequest,
        adminId: Long,
    ): ResponseEntity<BaseResponse<AdminFoodDetailResponse>>

    @Operation(summary = "승인", description = "PENDING_REVIEW → READY. 재료가 조사됐고 이미지가 있어야 한다(아니면 409 FOOD-005, payload.reason=NO_INGREDIENTS|NO_IMAGE). 이미 READY 면 200 멱등.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "승인"), ApiResponse(responseCode = "409", description = "전제 미충족·비허용 전이(FOOD-005)")])
    fun approve(id: Long, adminId: Long): ResponseEntity<BaseResponse<AdminFoodTransitionResponse>>

    @Operation(summary = "반려", description = "PENDING_REVIEW → FAILED. 사유 필수, 반려 횟수 +1.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "반려"), ApiResponse(responseCode = "400", description = "사유 누락"), ApiResponse(responseCode = "409", description = "비허용 전이(FOOD-005)")])
    fun reject(id: Long, request: AdminFoodRejectRequest, adminId: Long): ResponseEntity<BaseResponse<AdminFoodTransitionResponse>>

    @Operation(summary = "음식 삭제(소프트)", description = "삭제 표시 + 벡터 DELETE 예약. 이미 삭제된 음식은 200 멱등.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "삭제"), ApiResponse(responseCode = "400", description = "없는 음식(FOOD-001)")])
    fun deleteFood(id: Long, adminId: Long): ResponseEntity<BaseResponse<AdminFoodTransitionResponse>>

    @Operation(summary = "음식 복구", description = "삭제된 음식을 활성으로 되돌린다. READY 였으면 벡터 UPSERT 예약.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "복구 — 상세 응답"), ApiResponse(responseCode = "400", description = "없는 음식(FOOD-001)")])
    fun restoreFood(id: Long, adminId: Long): ResponseEntity<BaseResponse<AdminFoodDetailResponse>>

    @Operation(
        summary = "상태 전이",
        description = "APPROVE·REJECT(사유)·RESUBMIT(FAILED→PENDING_REVIEW/PENDING_IMAGE, 실패 사유 정리)·UNPUBLISH(READY→PENDING_REVIEW, 검색 제외). 허용되지 않으면 409(FOOD-005, payload.allowed[]).",
    )
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "전이"), ApiResponse(responseCode = "409", description = "비허용 전이(FOOD-005)")])
    fun transition(id: Long, request: AdminFoodTransitionRequest, adminId: Long): ResponseEntity<BaseResponse<AdminFoodTransitionResponse>>
}
