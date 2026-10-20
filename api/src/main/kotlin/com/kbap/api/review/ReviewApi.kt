package com.kbap.api.review

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.Page
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.ResponseEntity

@Tag(
    name = "리뷰",
    description = "음식 리뷰 작성·수정·삭제 API — 경로는 /api/reviews(URI 무버전), 버전은 X-API-Version 헤더(기본 1.0, 미지원 버전 400)로 전달한다",
)
@SecurityRequirement(name = "bearerAuth")
interface ReviewApi {
    @Operation(
        summary = "리뷰 작성",
        description = """
            음식에 리뷰를 작성한다. 별점(1~5)과 본문은 필수, 사진(최대 3장)·식당(place)은 옵션이다.
            본문은 앞뒤 공백을 뺀 코드 포인트 10자 이상·최대 1000자다 — 없음·빈 문자열·공백만·10자 미만은 400 REVIEW-005(payload.minLength=10).
            사진은 REVIEW 용도 presigned 업로드를 완료한 본인 소유 경로만 허용한다.
            place 는 식당 검색(GET /api/v1/places)에서 고른 항목을 그대로 넣는다 — 고르지 않았으면 생략한다.
            같은 회원이 같은 음식에 여러 건 작성할 수 있다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "작성 성공"),
            ApiResponse(responseCode = "400", description = "검증 실패(별점 범위·본문 1000자 초과·사진 수), 본문 10자 미만(REVIEW-005), 미존재 음식(FOOD-001), 미소유 이미지(REVIEW-003)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
        ],
    )
    @ApiErrors(
        ErrorCode.FOOD_NOT_FOUND,
        ErrorCode.FOOD_NOT_PUBLIC,
        ErrorCode.REVIEW_IMAGE_NOT_VERIFIED,
        ErrorCode.REVIEW_BODY_TOO_SHORT,
    )
    fun create(memberId: Long, request: ReviewCreateRequest): ResponseEntity<BaseResponse<ReviewResponse>>

    @Operation(
        summary = "리뷰 수정",
        description = "본인 리뷰의 별점·본문·사진·식당을 수정한다. content·imagePaths·place 는 보낸 값으로 전량 교체된다(imagePaths·place 는 생략 시 제거). 본문은 작성과 같은 규칙(앞뒤 공백 제외 10자 이상)이라 생략·10자 미만이면 400 REVIEW-005 이고 기존 리뷰는 그대로다. 작성 시점 국적 스냅샷은 바뀌지 않는다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "수정 성공"),
            ApiResponse(responseCode = "400", description = "미존재/삭제된 리뷰(REVIEW-001), 검증 실패, 본문 10자 미만(REVIEW-005), 미소유 이미지(REVIEW-003)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
            ApiResponse(responseCode = "403", description = "타인 리뷰(REVIEW-002)"),
        ],
    )
    @ApiErrors(
        ErrorCode.REVIEW_NOT_FOUND,
        ErrorCode.REVIEW_FORBIDDEN,
        ErrorCode.REVIEW_IMAGE_NOT_VERIFIED,
        ErrorCode.REVIEW_BODY_TOO_SHORT,
    )
    fun update(
        memberId: Long,
        @Parameter(description = "수정할 리뷰 id", example = "42") reviewId: Long,
        request: ReviewUpdateRequest,
    ): ResponseEntity<BaseResponse<ReviewResponse>>

    @Operation(
        summary = "리뷰 삭제",
        description = "본인 리뷰를 소프트 삭제한다. 삭제된 리뷰는 목록·평점 집계에서 즉시 제외된다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "삭제 성공"),
            ApiResponse(responseCode = "400", description = "미존재/이미 삭제된 리뷰(REVIEW-001)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
            ApiResponse(responseCode = "403", description = "타인 리뷰(REVIEW-002)"),
        ],
    )
    @ApiErrors(
        ErrorCode.REVIEW_NOT_FOUND,
        ErrorCode.REVIEW_FORBIDDEN,
    )
    fun remove(
        memberId: Long,
        @Parameter(description = "삭제할 리뷰 id", example = "42") reviewId: Long,
    ): ResponseEntity<BaseResponse<Unit>>

    @Operation(
        summary = "리뷰 좋아요 등록/취소",
        description = """
            리뷰 좋아요 상태를 지정한다. liked=true 는 등록, liked=false 는 취소.
            회원당 리뷰 하나에 좋아요는 1개만 유지되며, 같은 상태로 다시 요청해도 성공(멱등)한다.
            등록(liked=true)은 리뷰가 존재해야 하고, 취소(liked=false)는 좋아요가 없어도 성공한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "처리 성공(같은 상태 재요청 포함)"),
            ApiResponse(responseCode = "400", description = "liked 파라미터 누락, 등록 시 미존재/삭제된 리뷰(REVIEW-001)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
        ],
    )
    @ApiErrors(ErrorCode.REVIEW_NOT_FOUND)
    fun like(
        memberId: Long,
        @Parameter(description = "대상 리뷰 id", example = "42") reviewId: Long,
        @Parameter(description = "지정할 좋아요 상태 — true 등록, false 취소", example = "true") liked: Boolean,
    ): ResponseEntity<BaseResponse<Unit>>

    @Operation(
        summary = "리뷰 목록 (전체·음식별) — 비회원 조회 가능",
        description = """
            리뷰를 50건 keyset 커서 방식으로 조회한다. 인증 없이(비회원) 호출할 수 있다.
            정렬(sort)·필터(foodId·countryCode·minRating·maxRating)를 조합할 수 있으며, 커서는 발급 시점의 정렬·필터 조합에 종속된다 —
            정렬 옵션이나 필터를 바꾸면 기존 커서를 버리고 첫 페이지부터 다시 조회해야 한다(다른 조합의 커서는 400 또는 순서 미보장).
            nextCursor 는 불투명 토큰이다(해석·조립 금지, 그대로 echo).
            foodId 를 주면 그 음식의 리뷰만, 생략하면 서비스 전체 리뷰를 내려준다(리뷰 피드 화면).
            countryCode 를 주면 작성 시점 국적 스냅샷이 정확히 일치하는 리뷰만 내려간다(리뷰가 없는 코드는 빈 목록).
            회원 조회는 차단한 회원의 리뷰가 제외된다(다른 회원에게는 그대로 노출). **신고는 목록을 바꾸지 않는다** — 신고한 리뷰도 신고자에게 그대로 보인다.
            삭제된 음식의 리뷰는 전원 제외, 탈퇴한 회원의 리뷰는 author=null·authorWithdrawn=true 로 노출된다.
            비회원 조회의 likedByMe 는 항상 false 다. lang 은 비회원도 필수(누락 400)다.
            각 리뷰에는 음식 요약(food — lang 으로 해석한 이름·대표 이미지)이 포함된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "400", description = "lang 누락, 미존재 음식(FOOD-001), 비정상 커서(FOOD-002)"),
        ],
    )
    @ApiErrors(
        ErrorCode.FOOD_NOT_FOUND,
        ErrorCode.FOOD_NOT_PUBLIC,
        ErrorCode.INVALID_CURSOR,
    )
    fun listReviews(
        memberId: Long?,
        @Parameter(description = "리뷰를 조회할 음식 id — 생략 시 전체 리뷰", example = "1") foodId: Long?,
        @ParameterObject request: ReviewListRequest,
    ): ResponseEntity<BaseResponse<ReviewListPage>>

    @Operation(
        summary = "리뷰 단건 — 비회원 조회 가능",
        description = """
            리뷰 한 건을 목록 항목과 같은 모양으로 조회한다(리뷰 상세 화면·딥링크). 인증 없이(비회원) 호출할 수 있다.
            음식 요약(food)은 항상 채워진다. 본인 리뷰인데 음식이 삭제된 경우만 food 필드가 생략된다.
            삭제·존재하지 않음·차단한 작성자·음식이 삭제된 타인 리뷰는 모두 REVIEW-001 이다. 본인 리뷰는 차단·음식 판정과 무관하게 보인다.
            비회원 조회의 likedByMe 는 항상 false 다. lang 은 비회원도 필수(누락 400)다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "400", description = "lang 누락·reviewId 숫자 아님(COMMON-002), 볼 수 없는 리뷰(REVIEW-001)"),
        ],
    )
    @ApiErrors(ErrorCode.REVIEW_NOT_FOUND)
    fun getReview(
        memberId: Long?,
        @Parameter(description = "조회할 리뷰 id", example = "42") reviewId: Long,
        @ParameterObject request: ReviewDetailRequest,
    ): ResponseEntity<BaseResponse<ReviewResponse>>

    @Operation(
        summary = "내 리뷰 목록",
        description = """
            내가 쓴 리뷰를 최신순 50건 keyset 커서 방식으로 조회한다(프로필 탭 진입).
            각 리뷰에는 음식 요약(food — lang 으로 해석한 이름·대표 이미지)이 포함되며, 음식이 삭제됐으면 food 는 null 이다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "400", description = "lang 누락, 비정상 커서(FOOD-002)"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 없음/만료"),
        ],
    )
    @ApiErrors(ErrorCode.INVALID_CURSOR)
    fun listMyReviews(
        memberId: Long,
        @ParameterObject request: ReviewPageRequest,
    ): ResponseEntity<BaseResponse<Page<ReviewResponse>>>

    @Operation(
        summary = "리뷰 목록 조회(3.2 이상 — 가공식품 리뷰 포함)",
        description = """
            3.2 이상 헤더의 공개 피드. 3.1 이하와 같은 파라미터·정렬이며 가공식품 리뷰가 섞인다 —
            가공식품 리뷰는 targetType=PROCESSED_FOOD, processedFood 요약이 있고 food 는 생략된다.
            foodId 를 주면 그 음식 리뷰만(가공식품 제외). 3.1 이하 응답에는 가공식품 리뷰가 나오지 않는다.
        """,
    )
    @ApiErrors(ErrorCode.FOOD_NOT_PUBLIC, ErrorCode.INVALID_CURSOR)
    fun listReviewsWithProcessedFood(
        memberId: Long?,
        @Parameter(description = "리뷰를 조회할 음식 id — 생략 시 전체 리뷰", example = "1") foodId: Long?,
        @ParameterObject request: ReviewListRequest,
    ): ResponseEntity<BaseResponse<ReviewListPage>>

    @Operation(
        summary = "내 리뷰 목록 조회(3.2 이상 — 가공식품 리뷰 포함)",
        description = "3.2 이상 헤더의 내 리뷰. 가공식품 리뷰가 섞인다(targetType=PROCESSED_FOOD·processedFood 요약). 3.1 이하 응답에는 나오지 않는다.",
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiErrors(ErrorCode.INVALID_CURSOR)
    fun listMyReviewsWithProcessedFood(
        memberId: Long,
        @ParameterObject request: ReviewPageRequest,
    ): ResponseEntity<BaseResponse<Page<ReviewResponse>>>

    @Operation(
        summary = "가공식품 리뷰 작성(3.2 이상)",
        description = """
            회원 전용. 본문 10~1000자(REVIEW-005), 사진 최대 3장(업로드 완료 경로). 서빙·친절 평점·위치는 없다.
            회원 리뷰 수 +1·스캔 무제한 해제·랭킹 리뷰 점수는 음식 리뷰와 같고, 음식 다양성 보너스는 없다.
            쓰기 플래그가 꺼져 있으면 403 PFOOD-005. 없는 제품 404 PFOOD-001, 생산종료 410 PFOOD-002.
            수정·삭제·좋아요·신고·번역은 /reviews/{reviewId} 를 그대로 쓴다.
        """,
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiErrors(
        ErrorCode.PROCESSED_FOOD_REVIEW_CLOSED,
        ErrorCode.PROCESSED_FOOD_NOT_FOUND,
        ErrorCode.PROCESSED_FOOD_DISCONTINUED,
        ErrorCode.REVIEW_BODY_TOO_SHORT,
    )
    fun createProcessedFoodReview(
        memberId: Long,
        @Parameter(description = "가공식품 id", example = "1201") processedFoodId: Long,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "가공식품 리뷰 작성 요청") request: ProcessedFoodReviewCreateRequest,
    ): ResponseEntity<BaseResponse<ReviewResponse>>

    @Operation(
        summary = "가공식품 리뷰 목록(3.2 이상)",
        description = "비회원 허용. 최신순 50건 keyset 커서. 회원이면 차단한 작성자의 리뷰는 빠진다. 없는 제품 404 PFOOD-001.",
    )
    @ApiErrors(ErrorCode.PROCESSED_FOOD_NOT_FOUND, ErrorCode.INVALID_CURSOR)
    fun listProcessedFoodReviews(
        memberId: Long?,
        @Parameter(description = "가공식품 id", example = "1201") processedFoodId: Long,
        @ParameterObject request: ReviewPageRequest,
    ): ResponseEntity<BaseResponse<Page<ReviewResponse>>>
}
