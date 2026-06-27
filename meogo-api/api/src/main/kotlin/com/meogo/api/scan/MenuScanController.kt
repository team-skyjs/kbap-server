package com.meogo.api.scan

import com.meogo.api.common.ApiPaths
import com.meogo.api.common.ApiResponse
import com.meogo.api.scan.dto.SubmitMenuScanRequest
import com.meogo.api.scan.dto.SubmitMenuScanResponse
import com.meogo.application.scan.SubmitMenuScanCommand
import com.meogo.application.scan.SubmitMenuScanUseCase
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/menu-scans")
class MenuScanController(
    private val submitMenuScanUseCase: SubmitMenuScanUseCase,
) {
    @PostMapping
    fun submit(
        @Valid @RequestBody request: SubmitMenuScanRequest,
    ): ResponseEntity<ApiResponse<SubmitMenuScanResponse>> {
        val itemIds = request.items.map { it.itemId }
        require(itemIds.toSet().size == itemIds.size) { "itemId 는 요청 내에서 중복될 수 없습니다" }

        val result = submitMenuScanUseCase.submit(request.toCommand())
        return ResponseEntity.ok(ApiResponse.ok(SubmitMenuScanResponse.from(result)))
    }
}

private fun SubmitMenuScanRequest.toCommand(): SubmitMenuScanCommand =
    SubmitMenuScanCommand(
        items = items.map { item ->
            val box = item.boundingBox!!
            SubmitMenuScanCommand.Item(
                itemId = item.itemId!!,
                rawMenuName = item.rawMenuName!!,
                boundingBox = SubmitMenuScanCommand.Box(
                    x = box.x!!,
                    y = box.y!!,
                    width = box.width!!,
                    height = box.height!!,
                ),
            )
        },
    )
