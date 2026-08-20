package com.kbap.api.order

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "주문 저장 결과")
data class OrderCreateResponse(
    @field:Schema(description = "생성된 주문 식별자", example = "123")
    val orderId: Long,
)
