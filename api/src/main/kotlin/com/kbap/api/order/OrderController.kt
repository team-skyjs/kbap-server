package com.kbap.api.order

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API + "/orders")
class OrderController(
    private val orderService: OrderService,
) : OrderApi {
    @PostMapping
    override fun placeOrder(
        @AuthMemberId memberId: Long,
        @Valid @RequestBody request: OrderCreateRequest,
    ): ResponseEntity<BaseResponse<OrderCreateResponse>> =
        ResponseEntity.ok(BaseResponse.ok(OrderCreateResponse(orderService.createOrder(memberId, request))))

    @GetMapping
    override fun getOrders(
        @AuthMemberId memberId: Long,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "10") size: Int,
    ): ResponseEntity<BaseResponse<OrderListPage>> =
        ResponseEntity.ok(BaseResponse.ok(orderService.getOrderPage(memberId, cursor, size)))

    @GetMapping("/{orderId}")
    override fun getOrderDetail(
        @AuthMemberId memberId: Long,
        @PathVariable orderId: Long,
    ): ResponseEntity<BaseResponse<OrderDetailResponse>> =
        ResponseEntity.ok(BaseResponse.ok(orderService.getOrderDetail(memberId, orderId)))
}
