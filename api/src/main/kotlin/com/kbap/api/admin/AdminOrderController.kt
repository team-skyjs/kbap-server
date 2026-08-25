package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthAdminId
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping(ApiPaths.ADMIN + "/orders", version = "1.0+")
class AdminOrderController(
    private val adminOrderService: AdminOrderService,
) : AdminOrderApi {
    @GetMapping
    override fun getOrders(
        @RequestParam(required = false) memberId: Long?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<BaseResponse<AdminOrderPageResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminOrderService.getOrderPage(memberId, from, to, AdminPaging.page(page), AdminPaging.size(size))))

    @GetMapping("/{id}")
    override fun getOrder(@PathVariable id: Long): ResponseEntity<BaseResponse<AdminOrderDetailResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminOrderService.getOrderDetail(id)))

    @DeleteMapping("/{id}")
    override fun deleteOrder(@PathVariable id: Long, @AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminOrderDeleteResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminOrderService.deleteOrder(adminId, id)))
}
