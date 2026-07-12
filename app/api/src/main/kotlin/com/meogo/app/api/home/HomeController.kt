package com.meogo.app.api.home

import com.meogo.app.api.common.ApiPaths
import com.meogo.app.api.common.BaseResponse
import com.meogo.app.api.common.auth.AuthMemberIdOrNull
import com.meogo.application.client.home.HomeQueryUseCase
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/home")
class HomeController(
    private val homeQueryUseCase: HomeQueryUseCase,
) : HomeApi {
    override fun home(
        @AuthMemberIdOrNull memberId: Long?,
    ): ResponseEntity<BaseResponse<HomeResponse>> {
        val result = homeQueryUseCase.getHome(memberId)
        return ResponseEntity.ok(BaseResponse.ok(HomeResponse.from(result, authenticated = memberId != null)))
    }
}
