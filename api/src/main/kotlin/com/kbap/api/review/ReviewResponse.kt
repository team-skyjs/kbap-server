package com.kbap.api.review

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.review.model.PlaceSource
import com.kbap.common.domain.review.model.Review
import com.kbap.common.domain.review.model.ReviewTargetType
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.domain.review.model.ReviewPlace
import com.kbap.common.util.ImageUrls
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.ZoneId

@Schema(description = "리뷰 대상 음식 요약 — 목록·단건 조회에서 채워지며, 음식이 삭제됐으면 null")
data class ReviewFoodResponse(
    @field:Schema(description = "음식 id", example = "1")
    val foodId: Long,

    @field:Schema(description = "요청 언어(lang)로 해석한 음식 표시 이름", example = "Kimbap")
    val name: String,

    @field:Schema(description = "한국어 표시명 — 요청 언어명과 같으면(lang=ko) null", example = "설렁탕", nullable = true)
    val koreanName: String?,

    @field:Schema(description = "음식 대표 이미지 URL(없으면 null)")
    val imageUrl: String?,
) {
    companion object {
        fun from(food: Food, lang: LanguageCode, imagePublicBaseUrl: String): ReviewFoodResponse {
            val name = food.displayName(lang)
            return ReviewFoodResponse(
                foodId = food.id,
                name = name,
                koreanName = food.displayName(LanguageCode.KO).takeIf { it != name },
                imageUrl = ImageUrls.resolve(imagePublicBaseUrl, food.imageRef),
            )
        }
    }
}

@Schema(description = "리뷰 단건 응답")
data class ReviewResponse(
    @field:Schema(description = "리뷰 id", example = "42")
    val reviewId: Long,

    @field:Schema(description = "별점(1~5)", example = "4")
    val rating: Int,

    @field:Schema(description = "제공 속도 평가 — 항상 0~5 정수, 0 은 평가 안 함", example = "5")
    val servingSpeed: Int,

    @field:Schema(description = "직원 친절도 평가 — 항상 0~5 정수, 0 은 평가 안 함", example = "4")
    val staffKindness: Int,

    @field:Schema(description = "맵기(1~5) — 가공식품 리뷰만, 미입력·음식 리뷰는 null", example = "3", nullable = true)
    val spiciness: Int? = null,

    @field:Schema(description = "당도(1~5) — 가공식품 리뷰만, 미입력·음식 리뷰는 null", example = "2", nullable = true)
    val sweetness: Int? = null,

    @field:Schema(description = "리뷰 본문(없으면 null)", example = "정말 맛있어요")
    val content: String?,

    @field:Schema(
        description = "리뷰 본문의 언어 — 앱 언어 코드(ko·zh-Hans·en·ja·zh-Hant·vi·id·th·ru·es) 또는 null. " +
            "서버가 저장 시 본문만 보고 판별한다. 판별이 애매하거나 본문이 없거나 앱이 지원하지 않는 언어면 null 이다. " +
            "클라이언트는 이 값이 앱 언어와 같을 때만 번역 버튼을 숨기고, null 이면 보여 준다.",
        example = "ko",
        nullable = true,
    )
    val language: String?,

    @field:Schema(description = "리뷰 사진 URL 목록(없으면 빈 배열)")
    val imageUrls: List<String>,

    @field:Schema(description = "작성 시각(epoch millis)", example = "1755142800000")
    val createdAt: Long,

    @field:Schema(description = "작성자 프로필(닉네임·랭킹·현재 국적). 탈퇴한 회원이면 null.", nullable = true)
    val author: ReviewAuthorResponse?,

    @field:Schema(description = "작성자가 탈퇴했는지 — true 면 author 가 null 이고 클라이언트가 로케일에 맞는 탈퇴 표시를 붙인다", example = "false")
    val authorWithdrawn: Boolean,

    @field:Schema(description = "좋아요 수", example = "3")
    val likeCount: Long,

    @field:Schema(description = "조회 회원이 좋아요를 눌렀는지", example = "true")
    val likedByMe: Boolean,

    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:Schema(description = "리뷰 대상 음식 요약. 목록·단건 조회에서 채워지며, 채워지지 않는 맥락(작성·수정 응답·음식 상세 동봉·삭제된 음식)에서는 필드 자체가 생략된다.", nullable = true)
    val food: ReviewFoodResponse? = null,

    @field:Schema(description = "작성 시 고른 식당 정보. 고르지 않았으면 null.", nullable = true)
    val place: ReviewPlaceResponse? = null,

    @field:Schema(description = "리뷰 대상 — FOOD(음식) / PROCESSED_FOOD(가공식품 — 3.2 이상 응답에만 나온다). 가공식품 리뷰는 servingSpeed·staffKindness 가 0, place 가 null 이다")
    val targetType: ReviewTargetType = ReviewTargetType.FOOD,

    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:Schema(description = "리뷰 대상 가공식품 요약 — 가공식품 리뷰에만 있고 음식 리뷰에서는 필드 자체가 생략된다", nullable = true)
    val processedFood: ReviewProcessedFoodResponse? = null,
) {
    companion object {
        fun from(
            review: Review,
            imagePublicBaseUrl: String,
            author: ReviewAuthorResponse?,
            likeCount: Long,
            likedByMe: Boolean,
            food: ReviewFoodResponse? = null,
            processedFood: ReviewProcessedFoodResponse? = null,
        ): ReviewResponse =
            ReviewResponse(
                reviewId = review.id,
                rating = review.rating,
                servingSpeed = review.servingSpeedRating,
                staffKindness = review.staffKindnessRating,
                spiciness = review.spicinessRating,
                sweetness = review.sweetnessRating,
                content = review.content,
                language = review.language,
                imageUrls = review.imageRefs.orEmpty().mapNotNull { ImageUrls.resolve(imagePublicBaseUrl, it) },
                createdAt = review.createdAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                author = author,
                authorWithdrawn = author == null,
                likeCount = likeCount,
                likedByMe = likedByMe,
                food = food,
                place = review.place?.let(ReviewPlaceResponse::from),
                targetType = review.targetType,
                processedFood = processedFood,
            )
    }
}

@Schema(description = "리뷰에 저장된 위치 정보 — 각 항목은 저장 당시 결측이면 null")
data class ReviewPlaceResponse(
    @field:Schema(description = "위치 출처 — GOOGLE_PLACE(검색에서 고른 식당) / MANUAL(사용자 입력 식당명 텍스트) / AUTHOR_LOCATION(작성자 좌표, 식당 항목 null) / KAKAO_PLACE(과거 저장분)")
    val source: PlaceSource?,

    @field:Schema(description = "Google 장소 식별자(GOOGLE_PLACE 출처일 때만, 그 외 null)", example = "ChIJN1t_tDeuEmsRUsoyG83frY4")
    val placeId: String?,

    @field:Schema(description = "식당명", example = "한밥집 강남점")
    val name: String?,

    @field:Schema(description = "주소", example = "서울 강남구 테헤란로 123")
    val address: String?,

    @field:Schema(description = "위도", example = "37.4979502")
    val latitude: BigDecimal?,

    @field:Schema(description = "경도", example = "127.0276368")
    val longitude: BigDecimal?,
) {
    companion object {
        fun from(place: ReviewPlace): ReviewPlaceResponse =
            ReviewPlaceResponse(
                source = place.source,
                placeId = place.placeId,
                name = place.name,
                address = place.address,
                latitude = place.latitude,
                longitude = place.longitude,
            )
    }
}

@Schema(description = "리뷰 대상 가공식품 요약")
data class ReviewProcessedFoodResponse(
    @field:Schema(description = "가공식품 id", example = "1201")
    val processedFoodId: Long,
    @field:Schema(description = "품목보고번호", example = "19700001001")
    val reportNo: String,
    @field:Schema(description = "제품명(표시 원문)", example = "새우깡")
    val productName: String,
    @field:Schema(description = "제조사 — 없으면 null", example = "농심", nullable = true)
    val maker: String?,
    @field:Schema(description = "제품 이미지 URL — 없으면 null", nullable = true)
    val imageUrl: String?,
) {
    companion object {
        fun from(product: ProcessedFoodProduct) = ReviewProcessedFoodResponse(
            product.id, product.reportNo, product.productName, product.displayMaker(), ProcessedFoodProduct.secureImageUrl(product.imageUrl),
        )
    }
}
