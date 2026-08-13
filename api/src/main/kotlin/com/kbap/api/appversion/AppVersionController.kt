package com.kbap.api.appversion

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API + "/app-version")
class AppVersionController(
    private val appVersionService: AppVersionService,
) : AppVersionApi {
    @GetMapping
    override fun getAppVersion(): ResponseEntity<BaseResponse<AppVersionResponse>> =
        ResponseEntity.ok(BaseResponse.ok(appVersionService.getAppVersion()))
}
