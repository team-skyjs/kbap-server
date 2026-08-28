package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import java.time.LocalDate

@Tag(name = "관리자 주문", description = "주문 목록(회원·기간)·상세(항목·메뉴판 사진·좌표)·삭제")
@SecurityRequirement(name = "bearerAuth")
interface AdminOrderApi {
    @Operation(summary = "주문 목록", description = "최신순. `from`/`to` 는 주문일(YYYY-MM-DD, 양끝 포함). 항목 수·총 수량·총액·메뉴판 사진 URL 동봉.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회 성공"), ApiResponse(responseCode = "403", description = "AUTH-008")])
    fun getOrders(
        memberId: Long?,
        @Parameter(description = "시작일", example = "2026-08-01") from: LocalDate?,
        @Parameter(description = "종료일(포함)", example = "2026-08-31") to: LocalDate?,
        page: Int,
        size: Int,
    ): ResponseEntity<BaseResponse<AdminOrderPageResponse>>

    @Operation(summary = "주문 상세", description = "항목별 음식명·이미지·수량·단가, 메뉴판 사진 URL, 좌표·주소.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회 성공"), ApiResponse(responseCode = "404", description = "없는 주문(ORDER-002)")])
    fun getOrder(id: Long): ResponseEntity<BaseResponse<AdminOrderDetailResponse>>

    @Operation(summary = "주문 삭제(소프트)", description = "주문과 항목을 함께 소프트 삭제한다.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "삭제"), ApiResponse(responseCode = "404", description = "없는 주문(ORDER-002)")])
    fun deleteOrder(id: Long, adminId: Long): ResponseEntity<BaseResponse<AdminOrderDeleteResponse>>
}
