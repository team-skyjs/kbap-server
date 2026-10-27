package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
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
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<BaseResponse<AdminScanPhotoFeed>> =
        ResponseEntity.ok(BaseResponse.ok(feedService.getScanPhotoFeed(isBot, memberId, cursor)))
}
