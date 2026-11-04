package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.scan.MenuNameEstimateService
import com.kbap.common.util.KoreanMenuNameNormalizer
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/menu-name-estimates", version = "1.0+")
class AdminMenuNameEstimateController(
    private val estimates: MenuNameEstimateService,
) : AdminMenuNameEstimateApi {
    @DeleteMapping("/{nameKey}")
    override fun deleteEstimate(
        @PathVariable nameKey: String,
    ): ResponseEntity<BaseResponse<AdminMenuNameEstimateDeleteResponse>> {
        val key = KoreanMenuNameNormalizer.matchKey(nameKey)
        return ResponseEntity.ok(BaseResponse.ok(AdminMenuNameEstimateDeleteResponse(key, key.isNotBlank() && estimates.deleteEstimate(key))))
    }
}

@Schema(description = "메뉴 이름 추정 캐시 삭제 결과")
data class AdminMenuNameEstimateDeleteResponse(
    @field:Schema(description = "정규화한 이름 키(matchKey)", example = "갈비탕")
    val nameKey: String,
    @field:Schema(description = "지운 행이 있었으면 true — 없어도 200(멱등)")
    val deleted: Boolean,
)
