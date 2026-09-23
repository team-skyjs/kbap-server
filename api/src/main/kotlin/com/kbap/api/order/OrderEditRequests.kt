package com.kbap.api.order

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.order.model.OrderItem
import com.kbap.common.domain.order.model.OrderPlaceSnapshot
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "주문 장소 교체 요청 — 식당 검색 결과 하나를 그대로 보낸다(리뷰 장소 태그와 같은 모양)")
data class OrderPlaceUpdateRequest(
    @field:NotBlank(message = "placeId 는 필수입니다")
    @field:Size(max = OrderPlaceSnapshot.MAX_EXTERNAL_ID_LENGTH, message = "placeId 는 최대 255자입니다")
    @field:Schema(description = "Google 장소 식별자 — 식당 검색 응답의 placeId 그대로", example = "ChIJN1t_tDeuEmsRUsoyG83frY4", requiredMode = Schema.RequiredMode.REQUIRED)
    val placeId: String?,

    @field:NotBlank(message = "name 은 필수입니다")
    @field:Size(max = OrderPlaceSnapshot.MAX_NAME_LENGTH, message = "식당명은 최대 100자입니다")
    @field:Schema(description = "식당명", example = "백년옥", requiredMode = Schema.RequiredMode.REQUIRED)
    val name: String?,

    @field:Size(max = OrderPlaceSnapshot.MAX_ADDRESS_LENGTH, message = "주소는 최대 200자입니다")
    @field:Schema(description = "주소(공급자 포맷). 없으면 생략", example = "서울 중구 소공로 51")
    val address: String? = null,

    @field:NotBlank(message = "language 는 필수입니다")
    @field:Schema(description = "검색에 쓴 언어(LanguageCode.code). 지원하지 않는 값은 en 으로 저장된다", example = "en", requiredMode = Schema.RequiredMode.REQUIRED)
    val language: String?,
) {
    fun toSnapshot(): OrderPlaceSnapshot =
        OrderPlaceSnapshot(
            externalId = placeId!!.trim(),
            name = name!!.trim(),
            address = address?.trim()?.takeIf { it.isNotEmpty() },
            language = LanguageCode.from(language!!.trim()).code,
        )
}

@Schema(description = "주문 항목 사진 교체 요청")
data class OrderItemImageUpdateRequest(
    @field:NotBlank(message = "imagePath 는 필수입니다")
    @field:Size(max = OrderItem.MAX_IMAGE_PATH_LENGTH, message = "imagePath 는 최대 512자입니다")
    @field:Schema(
        description = "본인이 purpose=ORDER_ITEM 으로 업로드 완료한 사진의 오브젝트 경로(도메인 없음)",
        example = "images/orders/2026/09/42_5f1c...jpg",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val imagePath: String?,
)
