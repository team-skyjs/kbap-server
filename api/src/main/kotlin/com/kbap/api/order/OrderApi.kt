package com.kbap.api.order

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "Order", description = "주문 내역")
interface OrderApi {
    @Operation(
        summary = "주문 내역 저장",
        description = """
            스캔 결과 화면에서 고른 메뉴들을 주문 1건으로 저장한다. 이름·가격은 저장 시점 스냅샷으로 고정된다.

            - **스캔 1회당 주문 1회** — 같은 imagePath 로 다시 저장하면 409(ORDER-003)다.
            - 좌표(latitude·longitude)는 옵셔널이며 함께 보내거나 함께 생략한다. 좌표가 오면 서버가
              도로명 주소로 변환해 좌표·주소를 저장한다 — 변환 실패는 주문을 막지 않는다(주소만 비움).
              좌표는 어떤 응답에도 노출되지 않는다.
            - imagePath 는 본인이 업로드한 스캔 이미지여야 한다(SCAN-001).
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "저장 성공"),
        ApiResponse(responseCode = "400", description = "검증 실패(빈 항목·수량 0·좌표 한쪽만), 이미지 미검증(SCAN-001), 존재하지 않는 음식(FOOD-001)"),
        ApiResponse(responseCode = "409", description = "이미 주문한 스캔(ORDER-003)"),
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiErrors(
        ErrorCode.SCAN_IMAGE_NOT_VERIFIED,
        ErrorCode.ORDER_ALREADY_PLACED,
        ErrorCode.FOOD_NOT_FOUND,
    )
    fun placeOrder(memberId: Long, request: OrderCreateRequest): ResponseEntity<BaseResponse<OrderCreateResponse>>

    @Operation(
        summary = "주문 리스트 조회",
        description = """
            본인의 주문을 최신순으로 조회한다. 커서 기반 페이지이며 `nextCursor` 를 그대로 되돌려주면 다음 페이지다.
            총 주문 수는 내려주지 않는다 — 커서 페이징이라 전체 개수를 알 필요가 없고, 리뷰 목록 계약과 동일하다.

            - 썸네일은 최대 4개이고, 음식 사진이 없으면 기본 대체 이미지 URL 이 채워진다.
            - `orderedAt` 은 epoch milliseconds, `roadAddress` 는 위치 미동의·변환 실패 시 null 이다.
            - 좌표는 어떤 경우에도 응답에 포함되지 않는다.
        """,
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "조회 성공"))
    @SecurityRequirement(name = "bearerAuth")
    @ApiErrors(ErrorCode.INVALID_CURSOR)
    fun getOrders(memberId: Long, cursor: String?, size: Int): ResponseEntity<BaseResponse<OrderListPage>>

    @Operation(
        summary = "주문 상세 조회",
        description = """
            본인 주문의 메뉴별 내역과 총가격을 조회한다.

            - `totalPrice` 는 가격이 있는 항목의 (단가 × 수량) 합이다. 가격이 없는 항목(스캔 미인식)은 제외된다.
            - 음식 사진은 항목마다 `items[].imageRef` 로 내려간다(사진이 없으면 기본 대체 이미지). 상세에는 별도 썸네일 목록이 없다.
            - 타인의 주문이거나 존재하지 않으면 404(ORDER-002)로 통일한다 — 주문 존재 여부를 노출하지 않는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "404", description = "주문 없음 또는 타인의 주문(ORDER-002)"),
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiErrors(ErrorCode.ORDER_NOT_FOUND)
    fun getOrderDetail(memberId: Long, orderId: Long): ResponseEntity<BaseResponse<OrderDetailResponse>>

    @Operation(
        summary = "주문 장소 교체",
        description = """
            본인 주문의 식당을 식당 검색 결과 하나로 바꾼다(자동 추정값을 사용자 확인값으로 덮어쓴다). 응답은 갱신된 주문 상세다.

            - 페이로드는 리뷰 장소 태그와 같은 모양(placeId·name·address·language)이며 검색 결과만 받는다 — 직접 입력·장소 지우기는 없다.
            - 타인의 주문이거나 존재하지 않으면 404(ORDER-002).
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "교체 성공 — 갱신된 주문 상세"),
        ApiResponse(responseCode = "400", description = "placeId·name·language 누락, 길이 초과(COMMON-002)"),
        ApiResponse(responseCode = "404", description = "주문 없음 또는 타인의 주문(ORDER-002)"),
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiErrors(ErrorCode.ORDER_NOT_FOUND)
    fun updatePlace(memberId: Long, orderId: Long, request: OrderPlaceUpdateRequest): ResponseEntity<BaseResponse<OrderDetailResponse>>

    @Operation(
        summary = "주문 항목 사진을 내 사진으로 교체",
        description = """
            항목 썸네일을 회원이 직접 올린 사진으로 바꾼다. 사진은 먼저 `POST /api/images` 에 purpose=`ORDER_ITEM` 으로 올려 완료해야 한다.
            응답은 갱신된 주문 상세이며 `items[].userImageUrl` 에 반영된다. `imageRef`·`hasPhoto` 는 카탈로그 기준 그대로다.

            - 본인이 ORDER_ITEM 용도로 올린 사진이 아니면 400(IMAGE-007) — 타인 사진·다른 용도(리뷰 등)로 올린 사진 모두.
            - 타인의 주문이거나 없으면 404(ORDER-002), 그 주문에 없는 항목이면 404(ORDER-004).
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "교체 성공 — 갱신된 주문 상세"),
        ApiResponse(responseCode = "400", description = "imagePath 누락(COMMON-002), 본인 ORDER_ITEM 업로드가 아님(IMAGE-007)"),
        ApiResponse(responseCode = "404", description = "주문 없음·타인 주문(ORDER-002), 항목이 그 주문에 없음(ORDER-004)"),
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiErrors(ErrorCode.ORDER_NOT_FOUND, ErrorCode.ORDER_ITEM_NOT_FOUND, ErrorCode.ORDER_ITEM_IMAGE_NOT_VERIFIED)
    fun replaceItemImage(
        memberId: Long,
        orderId: Long,
        itemId: Long,
        request: OrderItemImageUpdateRequest,
    ): ResponseEntity<BaseResponse<OrderDetailResponse>>

    @Operation(
        summary = "주문 항목 사진을 기본 사진으로 되돌리기",
        description = """
            회원 사진을 지우고 카탈로그 사진으로 되돌린다. 응답은 갱신된 주문 상세이며 `items[].userImageUrl` 이 null 이 된다.
            회원 사진이 없던 항목에 호출해도 200(멱등). 올렸던 사진 파일 자체는 지우지 않는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "원복 성공 — 갱신된 주문 상세"),
        ApiResponse(responseCode = "404", description = "주문 없음·타인 주문(ORDER-002), 항목이 그 주문에 없음(ORDER-004)"),
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiErrors(ErrorCode.ORDER_NOT_FOUND, ErrorCode.ORDER_ITEM_NOT_FOUND)
    fun restoreItemImage(memberId: Long, orderId: Long, itemId: Long): ResponseEntity<BaseResponse<OrderDetailResponse>>
}
