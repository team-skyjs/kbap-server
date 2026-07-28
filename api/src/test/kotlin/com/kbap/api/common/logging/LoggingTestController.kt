package com.kbap.api.common.logging

import com.kbap.api.common.ApiPaths
import com.kbap.api.common.BaseResponse
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// 테스트 전용 컨트롤러 — 테스트 소스셋에만 존재하며 루트 컴포넌트 스캔(com.kbap)이 테스트 컨텍스트에서만 등록한다.
@RestController
@RequestMapping(ApiPaths.V1 + "/test-logging")
class LoggingTestController {
    @GetMapping("/ok")
    fun ok(): ResponseEntity<BaseResponse<String>> = ResponseEntity.ok(BaseResponse.ok("ok"))

    @GetMapping("/business")
    fun business(): ResponseEntity<BaseResponse<String>> = throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)

    @GetMapping("/unhandled")
    fun unhandled(): ResponseEntity<BaseResponse<String>> = throw IllegalStateException("의도적 미처리 예외")
}
