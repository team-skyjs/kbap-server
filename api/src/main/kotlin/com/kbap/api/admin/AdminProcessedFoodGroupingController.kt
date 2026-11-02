package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/processed-foods", version = "1.0+")
class AdminProcessedFoodGroupingController(private val groupingService: AdminProcessedFoodGroupingService) : AdminProcessedFoodGroupingApi {
    @GetMapping("/grouping-preview")
    override fun getGroupingPreview(@RequestParam(defaultValue = "30") sampleSize: Int): ResponseEntity<BaseResponse<AdminProcessedFoodGroupingPreview>> =
        ResponseEntity.ok().header("Cache-Control", "no-store").body(BaseResponse.ok(groupingService.getGroupingPreview(sampleSize.coerceIn(1, 200))))
}
