package com.meogo.app.api.scan

import com.meogo.application.client.scan.usecase.SubmitMenuScanUseCase
import com.meogo.app.api.common.ApiPaths
import com.meogo.app.api.common.BaseResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/menu-scans")
class MenuScanController(
    private val submitMenuScanUseCase: SubmitMenuScanUseCase,
) : MenuScanApi {
    override fun submit(
        @RequestBody request: SubmitMenuScanRequest,
        lang: String?,
    ): ResponseEntity<BaseResponse<SubmitMenuScanResponse>> {
        val result = submitMenuScanUseCase.submit(request.toInput(lang))
        return ResponseEntity.ok(BaseResponse.ok(SubmitMenuScanResponse.from(result)))
    }
}
