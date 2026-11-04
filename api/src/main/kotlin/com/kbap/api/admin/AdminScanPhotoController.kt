package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/scan-photos", version = "1.0+")
class AdminScanPhotoController(
    private val feedService: AdminScanPhotoFeedService,
) : AdminScanPhotoApi {
    @GetMapping
    override fun getScanPhotoFeed(
        @RequestParam(defaultValue = "false") isBot: Boolean,
        @RequestParam(required = false) memberId: Long?,
        @RequestParam(required = false) excludeMemberIds: List<Long>?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "ALL") type: ScanPhotoTypeFilter,
    ): ResponseEntity<BaseResponse<AdminScanPhotoFeed>> =
        ResponseEntity.ok(BaseResponse.ok(feedService.getScanPhotoFeed(isBot, memberId, excludeMemberIds.orEmpty(), cursor, type)))

    @GetMapping("/{uploadedImageId}/scan-results")
    override fun getScanResults(
        @PathVariable uploadedImageId: Long,
    ): ResponseEntity<BaseResponse<AdminScanResults?>> =
        ResponseEntity.ok(BaseResponse.ok(feedService.getScanResultsOrNull(uploadedImageId)))
}
