package com.kbap.api.order

import com.kbap.api.core.BaseResponse
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
        ApiResponse(responseCode = "400", description = "검증 실패(빈 항목·수량 0·좌표 한쪽만) 또는 이미지 미검증(SCAN-001)"),
        ApiResponse(responseCode = "409", description = "이미 주문한 스캔(ORDER-003)"),
    )
    @SecurityRequirement(name = "bearerAuth")
    fun placeOrder(memberId: Long, request: OrderCreateRequest): ResponseEntity<BaseResponse<OrderCreateResponse>>

    @Operation(
        summary = "주문 리스트 조회",
        description = """
            본인의 주문을 최신순으로 조회한다. 커서 기반 페이지이며 `nextCursor` 를 그대로 되돌려주면 다음 페이지다.

            - 썸네일은 최대 4개이고, 음식 사진이 없으면 기본 대체 이미지 URL 이 채워진다.
            - `orderedAt` 은 epoch milliseconds, `roadAddress` 는 위치 미동의·변환 실패 시 null 이다.
            - 좌표는 어떤 경우에도 응답에 포함되지 않는다.
        """,
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "조회 성공"))
    @SecurityRequirement(name = "bearerAuth")
    fun getOrders(memberId: Long, cursor: String?, size: Int): ResponseEntity<BaseResponse<OrderListPage>>

    @Operation(
        summary = "주문 상세 조회",
        description = """
            본인 주문의 메뉴별 내역과 총가격을 조회한다.

            - `totalPrice` 는 가격이 있는 항목의 (단가 × 수량) 합이다. 가격이 없는 항목(스캔 미인식)은 제외된다.
            - 타인의 주문이거나 존재하지 않으면 404(ORDER-002)로 통일한다 — 주문 존재 여부를 노출하지 않는다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "404", description = "주문 없음 또는 타인의 주문(ORDER-002)"),
    )
    @SecurityRequirement(name = "bearerAuth")
    fun getOrderDetail(memberId: Long, orderId: Long): ResponseEntity<BaseResponse<OrderDetailResponse>>
}
