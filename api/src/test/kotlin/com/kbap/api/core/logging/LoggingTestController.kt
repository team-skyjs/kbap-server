package com.kbap.api.core.logging

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// 테스트 전용 컨트롤러 — 테스트 소스셋에만 존재하며 루트 컴포넌트 스캔(com.kbap)이 테스트 컨텍스트에서만 등록한다.
@RestController
@RequestMapping(ApiPaths.API + "/test-logging")
class LoggingTestController(
    private val foodRepository: com.kbap.common.domain.food.FoodJpaRepository,
    transactionManager: org.springframework.transaction.PlatformTransactionManager,
) {
    private val transaction = org.springframework.transaction.support.TransactionTemplate(transactionManager)
    private val bothHoldFirstLock = java.util.concurrent.CyclicBarrier(2)

    @GetMapping("/lock-conflict-wrapped")
    fun lockConflictWrapped(): ResponseEntity<BaseResponse<String>> =
        throw IllegalStateException("감싼 예외", org.springframework.dao.CannotAcquireLockException("잠금 대기 초과"))

    @GetMapping("/lock-both")
    fun lockBoth(
        @org.springframework.web.bind.annotation.RequestParam first: Long,
        @org.springframework.web.bind.annotation.RequestParam second: Long,
    ): ResponseEntity<BaseResponse<String>> {
        transaction.executeWithoutResult {
            foodRepository.findByIdForUpdate(first)
            bothHoldFirstLock.await(20, java.util.concurrent.TimeUnit.SECONDS)
            foodRepository.findByIdForUpdate(second)
        }
        return ResponseEntity.ok(BaseResponse.ok("ok"))
    }

    @GetMapping("/ok")
    fun ok(): ResponseEntity<BaseResponse<String>> = ResponseEntity.ok(BaseResponse.ok("ok"))

    @GetMapping("/business")
    fun business(): ResponseEntity<BaseResponse<String>> = throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)

    @GetMapping("/unhandled")
    fun unhandled(): ResponseEntity<BaseResponse<String>> = throw IllegalStateException("의도적 미처리 예외")
}
