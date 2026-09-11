package com.kbap.api.order

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "주문 리스트 페이지 — 최신 주문 순, 커서 기반")
data class OrderListPage(
    @field:Schema(description = "주문 카드 목록")
    val items: List<OrderSummaryResponse>,

    @field:Schema(description = "다음 페이지 존재 여부")
    val hasNext: Boolean,

    @field:Schema(
        description = "다음 페이지 커서(그대로 되돌려준다). 마지막 페이지면 null",
        example = "123",
        nullable = true,
    )
    val nextCursor: String? = null,
)

@Schema(description = "주문 카드 — 좌표는 노출하지 않는다")
data class OrderSummaryResponse(
    @field:Schema(description = "주문 식별자", example = "123")
    val orderId: Long,

    @field:Schema(description = "주문 시각(epoch milliseconds)", example = "1765700640000")
    val orderedAt: Long,

    @field:Schema(description = "주문 위치의 도로명 주소. 위치 미동의·변환 실패면 null", example = "서울 중구 소공로 51", nullable = true)
    val roadAddress: String?,

    @field:Schema(description = "주문한 음식 총 수량", example = "6")
    val totalQuantity: Int,

    @field:Schema(description = "주문한 음식 썸네일 URL — 최대 4개. READY 음식만 실사진, 준비중이거나 사진이 없으면 기본 대체 이미지")
    val thumbnails: List<String>,

    @field:Schema(
        description = "주문 시점에 스캔했던 메뉴판 사진 URL",
        example = "https://cdn.example.com/scan/42/menu.jpg",
    )
    val scanImageUrl: String,

    @field:Schema(
        description = "주문 좌표에서 자동 추정한 식당(공급자 거리순 첫 결과·사용자 확인값 아님). 좌표 없음·추정 실패면 null → 클라는 roadAddress 로 폴백",
        nullable = true,
    )
    val place: OrderPlaceResponse?,
)

@Schema(description = "주문 상세 — 카드 정보 + 메뉴별 내역")
data class OrderDetailResponse(
    @field:Schema(description = "주문 식별자", example = "123")
    val orderId: Long,

    @field:Schema(description = "주문 시각(epoch milliseconds)", example = "1765700640000")
    val orderedAt: Long,

    @field:Schema(description = "주문 위치의 도로명 주소", example = "서울 중구 소공로 51", nullable = true)
    val roadAddress: String?,

    @field:Schema(description = "주문한 음식 총 수량", example = "6")
    val totalQuantity: Int,

    @field:Schema(description = "주문 총가격 — 가격이 있는 항목의 (단가 × 수량) 합", example = "38500")
    val totalPrice: Int,

    @field:Schema(
        description = "주문 시점에 스캔했던 메뉴판 사진 URL — 목록의 scanImageUrl 과 동일",
        example = "https://cdn.example.com/scan/42/menu.jpg",
    )
    val scanImageUrl: String,

    @field:Schema(description = "주문한 메뉴별 내역 — 주문 시점 스냅샷")
    val items: List<OrderItemResponse>,

    @field:Schema(
        description = "주문 좌표에서 자동 추정한 식당(공급자 거리순 첫 결과·사용자 확인값 아님). 좌표 없음·추정 실패면 null → 클라는 roadAddress 로 폴백",
        nullable = true,
    )
    val place: OrderPlaceResponse?,
)

@Schema(description = "주문 좌표에서 자동 추정한 식당 스냅샷")
data class OrderPlaceResponse(
    @field:Schema(description = "공급자 외부 식별자(Google place id)", example = "ChIJN1t_tDeuEmsRUsoyG83frY4")
    val placeId: String,

    @field:Schema(description = "추정 식당명", example = "백년옥")
    val name: String,

    @field:Schema(description = "추정 식당 주소(공급자 포맷). 없을 수 있음", example = "서울 중구 소공로 51", nullable = true)
    val address: String?,

    @field:Schema(description = "해석 요청 언어(LanguageCode.code)", example = "en")
    val language: String,
) {
    companion object {
        fun from(snapshot: com.kbap.common.domain.order.model.OrderPlaceSnapshot?): OrderPlaceResponse? =
            snapshot?.let {
                OrderPlaceResponse(
                    placeId = it.externalId,
                    name = it.name,
                    address = it.address,
                    language = it.language,
                )
            }
    }
}

@Schema(description = "주문 항목 — 저장 시점 스냅샷")
data class OrderItemResponse(
    @field:Schema(description = "메뉴명", example = "순두부찌개")
    val menuName: String,

    @field:Schema(description = "수량", example = "2")
    val quantity: Int,

    @field:Schema(description = "단가(원화). 스캔에서 가격을 못 읽었으면 null — 총가격에서 제외된다", example = "9000", nullable = true)
    val price: Int?,

    @field:Schema(description = "음식 식별자", example = "7")
    val foodId: Long,

    @field:Schema(
        description = "음식 사진 URL — READY 음식만 실사진, 준비중(ready=false)이거나 사진이 없으면 기본 대체 이미지",
        example = "https://cdn.example.com/images/webp/sundubu.webp",
    )
    val imageRef: String,

    @field:Schema(
        description = "음식이 공개(READY) 상태인지. false 면 준비중 음식이라 음식 상세(GET /api/foods/{foodId})가 FOOD-001 — 상세 링크를 비활성화한다.",
        example = "true",
    )
    val ready: Boolean,
)
