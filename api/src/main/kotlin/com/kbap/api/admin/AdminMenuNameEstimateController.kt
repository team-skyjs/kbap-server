package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.JwtAuthenticationFilter
import com.kbap.api.scan.MenuNameEstimateService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.port.llm.MenuNameKind
import com.kbap.common.util.KoreanMenuNameNormalizer
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

@RestController
@RequestMapping(ApiPaths.ADMIN + "/menu-name-estimates", version = "1.0+")
class AdminMenuNameEstimateController(
    private val estimates: MenuNameEstimateService,
    private val adminEstimates: AdminMenuNameEstimateService,
    @Value("\${kbap.curation.environment:disabled}") private val environment: String,
) : AdminMenuNameEstimateApi {
    @GetMapping
    override fun getEstimates(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(defaultValue = "SCAN_COUNT") sort: AdminMenuNameEstimateSort,
        @RequestParam(required = false) kind: MenuNameKind?,
        @RequestParam(required = false) promoted: Boolean?,
        @RequestParam(required = false) foodStatus: AdminMenuNameEstimateFoodStatus?,
    ): ResponseEntity<BaseResponse<AdminMenuNameEstimatePage>> {
        if (page < 0 || size !in 1..MAX_PAGE_SIZE) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val filter = AdminMenuNameEstimateFilter(kind = kind, promoted = promoted, foodStatus = foodStatus, sort = sort)
        return ResponseEntity.ok(BaseResponse.ok(adminEstimates.getEstimatePage(filter, page, size)))
    }

    @PostMapping("/{id}/promote")
    override fun promoteEstimate(
        @PathVariable id: Long,
        @RequestHeader("X-Target-Environment") target: String,
        @RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminId: Long,
    ): ResponseEntity<BaseResponse<AdminMenuNameEstimatePromoteResult>> {
        if (target != environment) throw BusinessException(ErrorCode.FOOD_ENVIRONMENT_MISMATCH)
        return ResponseEntity.ok(BaseResponse.ok(adminEstimates.promote(id, adminId)))
    }

    @DeleteMapping("/{nameKey}")
    override fun deleteEstimate(
        @PathVariable nameKey: String,
    ): ResponseEntity<BaseResponse<AdminMenuNameEstimateDeleteResponse>> {
        val key = KoreanMenuNameNormalizer.matchKey(nameKey)
        return ResponseEntity.ok(BaseResponse.ok(AdminMenuNameEstimateDeleteResponse(key, key.isNotBlank() && estimates.deleteEstimate(key))))
    }
}

@Schema(description = "메뉴 이름 추정 캐시 삭제 결과")
private const val MAX_PAGE_SIZE = 100

@Schema(description = "AI 추정 메뉴 목록 한 페이지")
data class AdminMenuNameEstimatePage(
    val items: List<AdminMenuNameEstimateItem>,
    @field:Schema(description = "0부터")
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val hasNext: Boolean,
)

@Schema(description = "AI 추정 메뉴 한 행 + 같은 이름 키 음식 상태")
data class AdminMenuNameEstimateItem(
    val id: Long,
    @field:Schema(description = "정규화한 이름 키(matchKey) — 같은 키 음식의 korean_name", example = "갈비탕")
    val nameKey: String,
    @field:Schema(description = "DISH·DRINK·NOT_FOOD")
    val kind: String,
    val englishName: String?,
    val description: String?,
    val avoidanceCodes: List<String>,
    @field:Schema(description = "0~10, 판단 불가 -1")
    val spiciness: Int,
    val confidence: Double?,
    val model: String,
    val promptVersion: String,
    val createdAt: LocalDateTime,
    @field:Schema(description = "같은 키 음식의 스캔 이력(scan_history) 건수 — 음식이 없으면 0")
    val scanCount: Long,
    @field:Schema(description = "음식으로 등록(승격)한 시각 — 미등록이면 null")
    val promotedAt: LocalDateTime?,
    @field:Schema(description = "승격한 어드민 회원 id")
    val promotedBy: Long?,
    @field:Schema(description = "같은 키의 활성 음식 — 없으면 null")
    val food: AdminMenuNameEstimateFood?,
    @field:Schema(description = "활성 음식이 없고 같은 원래 이름으로 소프트 삭제된 음식이 있으면 그 id(가장 최근)")
    val deletedFoodId: Long?,
    @field:Schema(description = "승격 가능 — 아직 승격 안 했고 같은 키 음식이 READY 가 아님")
    val promotable: Boolean,
)

@Schema(description = "같은 이름 키 음식의 상태")
data class AdminMenuNameEstimateFood(
    val id: Long,
    val contentStatus: FoodContentStatus,
    @field:Schema(description = "대표 이미지가 있으면 true")
    val hasImage: Boolean,
    @field:Schema(description = "진행 중인 콘텐츠 요청(PENDING·SENT)이 있으면 true")
    val contentRequestPending: Boolean,
)

data class AdminMenuNameEstimateDeleteResponse(
    @field:Schema(description = "정규화한 이름 키(matchKey)", example = "갈비탕")
    val nameKey: String,
    @field:Schema(description = "지운 행이 있었으면 true — 없어도 200(멱등)")
    val deleted: Boolean,
)
