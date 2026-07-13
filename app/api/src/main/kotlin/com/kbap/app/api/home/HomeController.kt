package com.kbap.app.api.home

import com.kbap.app.api.common.ApiPaths
import com.kbap.app.api.common.BaseResponse
import com.kbap.app.api.common.auth.AuthMemberIdOrNull
import com.kbap.application.home.HomeApplicationService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/home")
class HomeController(
    private val homeApplicationService: HomeApplicationService,
) : HomeApi {
    override fun home(
        @AuthMemberIdOrNull memberId: Long?,
    ): ResponseEntity<BaseResponse<HomeResponse>> {
        val result = homeApplicationService.getHome(memberId)
        return ResponseEntity.ok(BaseResponse.ok(HomeResponse.from(result, authenticated = memberId != null)))
    }
}
