package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.PageRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalDateTime

data class AdminScanResponse(
    val id: Long,
    val memberId: Long,
    val memberNickname: String?,
    val foodId: Long?,
    val foodDisplayName: String?,
    val matched: Boolean,
    val price: Int?,
    val createdAt: LocalDateTime,
)

data class AdminScanPageResponse(
    val items: List<AdminScanResponse>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
)

@Service
class AdminScanService(
    private val scanHistoryRepository: ScanHistoryJpaRepository,
    private val memberRepository: MemberJpaRepository,
    private val foodRepository: FoodJpaRepository,
) {
    @Transactional(readOnly = true)
    fun getScanPage(memberId: Long?, unmatched: Boolean?, from: LocalDate?, to: LocalDate?, page: Int, size: Int): AdminScanPageResponse {
        val result = scanHistoryRepository.findAdminPage(memberId, unmatched, from?.atStartOfDay(), to?.plusDays(1)?.atStartOfDay(), PageRequest.of(page - 1, size))
        val scans = result.content
        val nicknames = memberRepository.findAllById(scans.map { it.memberId }.toSet()).associate { it.id to it.nickname }
        val foodNames = foodRepository.findAllById(scans.mapNotNull { it.foodId }.toSet()).associate { it.id to it.displayName }
        return AdminScanPageResponse(
            items = scans.map {
                AdminScanResponse(
                    id = it.id,
                    memberId = it.memberId,
                    memberNickname = nicknames[it.memberId],
                    foodId = it.foodId,
                    foodDisplayName = it.foodId?.let(foodNames::get),
                    matched = it.foodId != null,
                    price = it.price,
                    createdAt = it.createdAt,
                )
            },
            page = page,
            size = size,
            totalCount = result.totalElements,
            totalPages = totalPagesOf(result.totalElements, size),
        )
    }
}

@Tag(name = "관리자 스캔", description = "스캔 이력 조회 — 회원·미매칭·기간 필터")
@SecurityRequirement(name = "bearerAuth")
interface AdminScanApi {
    @Operation(summary = "스캔 이력", description = "최신순. `unmatched=true` 는 음식 매칭이 안 된 스캔만, `false` 는 매칭된 것만. `from`/`to` 는 스캔일(YYYY-MM-DD, 양끝 포함).")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회 성공"), ApiResponse(responseCode = "403", description = "AUTH-008")])
    fun getScans(
        memberId: Long?,
        @Parameter(description = "미매칭만/매칭만") unmatched: Boolean?,
        @Parameter(example = "2026-08-01") from: LocalDate?,
        @Parameter(example = "2026-08-31") to: LocalDate?,
        page: Int,
        size: Int,
    ): ResponseEntity<BaseResponse<AdminScanPageResponse>>
}

@RestController
@RequestMapping(ApiPaths.ADMIN + "/scans", version = "1.0+")
class AdminScanController(
    private val adminScanService: AdminScanService,
) : AdminScanApi {
    @GetMapping
    override fun getScans(
        @RequestParam(required = false) memberId: Long?,
        @RequestParam(required = false) unmatched: Boolean?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<BaseResponse<AdminScanPageResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminScanService.getScanPage(memberId, unmatched, from, to, AdminPaging.page(page), AdminPaging.size(size))))
}
