package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.JwtAuthenticationFilter
import com.kbap.common.domain.processedfood.model.CutoutReviewStatus
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@RestController
@RequestMapping(ApiPaths.ADMIN + "/processed-foods", version = "1.0+")
class AdminProcessedFoodCutoutReviewController(
    private val cutoutService: AdminProcessedFoodCutoutService,
) : AdminProcessedFoodCutoutReviewApi {
    @PostMapping("/cutout-images/bulk-register")
    override fun bulkRegister(
        @RequestBody items: List<AdminCutoutRegisterItem>,
    ): ResponseEntity<BaseResponse<AdminCutoutBulkRegisterResponse>> =
        ResponseEntity.ok(BaseResponse.ok(cutoutService.bulkRegister(items)))

    @GetMapping("/cutout-images/summary")
    override fun getCutoutReviewSummary(): ResponseEntity<BaseResponse<AdminCutoutReviewSummary>> =
        ResponseEntity.ok(BaseResponse.ok(cutoutService.getCutoutReviewSummary()))

    @GetMapping("/cutout-images")
    override fun getCutoutReviewPage(
        @RequestParam(required = false) status: CutoutReviewStatus?,
        @RequestParam(required = false) route: String?,
        @RequestParam(required = false) minCoverage: BigDecimal?,
        @RequestParam(required = false) maxCoverage: BigDecimal?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(required = false) sort: AdminCutoutReviewSort?,
        @RequestParam(required = false) q: String?,
    ): ResponseEntity<BaseResponse<AdminCutoutReviewPage>> =
        ResponseEntity.ok(BaseResponse.ok(cutoutService.getCutoutReviewPage(status, route, minCoverage, maxCoverage, cursor, size, sort, q)))

    @PostMapping("/{id}/cutout-image/review")
    override fun review(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminCutoutReviewRequest,
        @RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminId: Long,
    ): ResponseEntity<BaseResponse<AdminCutoutReviewItem>> =
        ResponseEntity.ok(BaseResponse.ok(cutoutService.reviewCutout(id, request.approved!!, request.reason, adminId, request.expectedCutoutImageUrl)))

    @PostMapping("/cutout-images/bulk-approve")
    override fun bulkApprove(
        @Valid @RequestBody request: AdminCutoutBulkApproveRequest,
        @RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminId: Long,
    ): ResponseEntity<BaseResponse<AdminCutoutBulkApproveResponse>> =
        ResponseEntity.ok(BaseResponse.ok(cutoutService.bulkApprove(request.minCoverage!!, request.dryRun, adminId)))
}

@Schema(description = "누끼 일괄 등록 항목")
data class AdminCutoutRegisterItem(
    @field:Schema(description = "가공식품 id", example = "1024")
    val productId: Long? = null,
    @field:Schema(description = "S3 오브젝트 키 — `{key-prefix}/processed/cutout/{품목보고번호}.png`", example = "dev/processed/cutout/20150445222185.png")
    val key: String? = null,
    @field:Schema(description = "누끼 생성 경로: vision·fallback", example = "vision")
    val route: String? = null,
    @field:Schema(description = "전경 비율 0~1(소수 넷째 자리까지 저장)", example = "0.6123", nullable = true)
    val coverage: BigDecimal? = null,
)

@Schema(description = "누끼 일괄 등록 결과 — 실패 항목만 사유와 함께 돌려주고 나머지는 저장한다")
data class AdminCutoutBulkRegisterResponse(
    @field:Schema(description = "저장한 항목 수(같은 오브젝트 재등록 포함)")
    val registered: Int,
    val failures: List<AdminCutoutRegisterFailure>,
)

@Schema(description = "누끼 일괄 등록 실패 항목")
data class AdminCutoutRegisterFailure(
    val productId: Long?,
    @field:Schema(description = "PRODUCT_NOT_FOUND · KEY_MISMATCH · INVALID_ROUTE · INVALID_COVERAGE · DUPLICATE(같은 요청에 같은 productId 가 둘 이상) · NOT_UPLOADED(오브젝트 없음·PNG 아님·2MB 초과·확인 실패)")
    val reason: String,
)

@Schema(description = "누끼 한 장 검수 요청")
data class AdminCutoutReviewRequest(
    @field:NotNull(message = "approved 는 필수입니다")
    @field:Schema(description = "true 면 승인, false 면 반려", requiredMode = Schema.RequiredMode.REQUIRED)
    val approved: Boolean? = null,
    @field:Size(max = ProcessedFoodProduct.CUTOUT_REVIEW_REASON_LENGTH, message = "reason 은 200자 이하여야 합니다")
    @field:Schema(description = "반려 사유(반려일 때만 저장, 최대 200자)", example = "연회색 배경 잔존", nullable = true)
    val reason: String? = null,
    @field:Schema(
        description = "관리자가 화면에서 본 누끼 URL(목록의 cutoutImageUrl 그대로) — 보내면 현재 누끼와 대조해 다르면 409 PFOOD-010(그사이 다시 등록됨). 없으면 대조하지 않는다",
        example = "https://cdn.example.com/dev/processed/cutout/20150445222185.png?v=1793000000",
        nullable = true,
    )
    val expectedCutoutImageUrl: String? = null,
)

@Schema(description = "누끼 조건 일괄 승인 요청 — PENDING · route=vision · coverage ≥ minCoverage")
data class AdminCutoutBulkApproveRequest(
    @field:NotNull(message = "minCoverage 는 필수입니다")
    @field:DecimalMin(value = "0", message = "minCoverage 는 0 이상이어야 합니다")
    @field:DecimalMax(value = "1", message = "minCoverage 는 1 이하여야 합니다")
    @field:Schema(description = "전경 비율 하한(이상)", example = "0.3", requiredMode = Schema.RequiredMode.REQUIRED)
    val minCoverage: BigDecimal? = null,
    @field:Schema(description = "true 면 세기만 하고 바꾸지 않는다", example = "true")
    val dryRun: Boolean = false,
)

@Schema(description = "누끼 조건 일괄 승인 결과")
data class AdminCutoutBulkApproveResponse(
    @field:Schema(description = "승인한(dryRun 이면 승인될) 건수")
    val count: Long,
    val dryRun: Boolean,
)

@Schema(description = "누끼 검수 목록 정렬 — RECENT = 검수 시각 최신순(같으면 id 큰 순). status 가 APPROVED·REJECTED 일 때만 적용, 그 밖에는 무시하고 id 오름차순")
enum class AdminCutoutReviewSort { RECENT }

@Schema(description = "누끼 검수 현황 — 누끼가 등록된 제품 수. 오늘은 KST(Asia/Seoul) 자정부터 다음 자정 전까지 검수한 것")
data class AdminCutoutReviewSummary(
    @field:Schema(description = "오늘(KST) 날짜", example = "2026-10-30")
    val date: LocalDate,
    @field:Schema(description = "검수 대기 수(경로 없는 수동 업로드 포함 전체)", example = "14561")
    val pending: Long,
    @field:Schema(description = "승인 수", example = "81")
    val approved: Long,
    @field:Schema(description = "반려 수", example = "3")
    val rejected: Long,
    @field:Schema(description = "오늘(KST) 승인 수", example = "81")
    val todayApproved: Long,
    @field:Schema(description = "오늘(KST) 반려 수", example = "3")
    val todayRejected: Long,
    @field:Schema(description = "생성 경로별 — vision·fallback 은 항상 있다(없으면 0). 경로 없는 수동 업로드는 위 전체에만 센다")
    val byRoute: Map<String, AdminCutoutReviewTally>,
)

@Schema(description = "누끼 검수 상태별 수")
data class AdminCutoutReviewTally(
    val pending: Long,
    val approved: Long,
    val rejected: Long,
    @field:Schema(description = "오늘(KST) 승인 수 — 현재 APPROVED 이면서 검수 시각이 오늘인 것")
    val todayApproved: Long,
    @field:Schema(description = "오늘(KST) 반려 수 — 현재 REJECTED 이면서 검수 시각이 오늘인 것")
    val todayRejected: Long,
)

@Schema(description = "누끼 검수 목록 한 쪽")
data class AdminCutoutReviewPage(
    val items: List<AdminCutoutReviewItem>,
    @field:Schema(
        description = "다음 쪽 커서(불투명 문자열 — 그대로 cursor 로 보낸다). 기본 정렬은 마지막 항목 id 의 숫자 문자열, RECENT 는 검수 시각·id 를 담은 값. 없으면 null",
        example = "120",
        nullable = true,
    )
    val nextCursor: String?,
)

@Schema(description = "누끼 검수 항목")
data class AdminCutoutReviewItem(
    val processedFoodId: Long,
    val reportNo: String,
    val productName: String,
    @field:Schema(description = "HACCP 원본 이미지(https)", nullable = true)
    val originalImageUrl: String?,
    @field:Schema(description = "누끼 이미지", nullable = true)
    val cutoutImageUrl: String?,
    @field:Schema(description = "앱이 받는 imageUrl — APPROVED 일 때만 누끼", nullable = true)
    val imageUrl: String?,
    val status: CutoutReviewStatus?,
    @field:Schema(description = "vision·fallback", nullable = true)
    val route: String?,
    @field:Schema(description = "전경 비율 0~1", nullable = true)
    val coverage: BigDecimal?,
    @field:Schema(description = "검수 시각", nullable = true)
    val reviewedAt: LocalDateTime?,
    @field:Schema(description = "검수 관리자 id", nullable = true)
    val reviewedBy: Long?,
    @field:Schema(description = "반려 사유", nullable = true)
    val reason: String?,
) {
    companion object {
        fun from(product: ProcessedFoodProduct) = AdminCutoutReviewItem(
            processedFoodId = product.id,
            reportNo = product.reportNo,
            productName = product.productName,
            originalImageUrl = ProcessedFoodProduct.secureImageUrl(product.imageUrl),
            cutoutImageUrl = product.cutoutImageUrl,
            imageUrl = product.displayImageUrl(),
            status = product.cutoutReviewStatus,
            route = product.cutoutRoute,
            coverage = product.cutoutCoverage,
            reviewedAt = product.cutoutReviewedAt,
            reviewedBy = product.cutoutReviewedBy,
            reason = product.cutoutReviewReason,
        )
    }
}
