package com.meogo.api.presentation.scan

import com.meogo.api.application.scan.usecase.SubmitMenuScanUseCase
import com.meogo.api.presentation.common.ApiPaths
import com.meogo.api.presentation.common.BaseResponse
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
    ): ResponseEntity<BaseResponse<SubmitMenuScanResponse>> {
        val result = submitMenuScanUseCase.submit(request.toInput())
        return ResponseEntity.ok(BaseResponse.ok(SubmitMenuScanResponse.from(result)))
    }
}
