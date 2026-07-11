package com.meogo.app.api.scan

import com.meogo.application.client.scan.usecase.ScanUseCase
import com.meogo.app.api.common.ApiPaths
import com.meogo.app.api.common.BaseResponse
import com.meogo.app.api.common.auth.AuthMemberId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/scans")
class ScanController(
    private val scanUseCase: ScanUseCase,
) : ScanApi {
    override fun scan(
        @AuthMemberId memberId: Long,
        @RequestBody request: ScanRequest,
    ): ResponseEntity<BaseResponse<ScanResponse>> {
        val result = scanUseCase.assessMenuBoard(request.toInput(memberId))
        return ResponseEntity.ok(BaseResponse.ok(ScanResponse.from(result)))
    }
}
