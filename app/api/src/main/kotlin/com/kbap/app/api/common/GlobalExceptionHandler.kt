package com.kbap.app.api.common

import com.kbap.core.error.BusinessException
import com.kbap.core.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<BaseResponse<Any>> {
        val message = e.bindingResult.allErrors
            .joinToString("; ") { error ->
                val field = (error as? org.springframework.validation.FieldError)?.field ?: error.objectName
                "$field: ${error.defaultMessage}"
            }
            .ifBlank { "잘못된 요청입니다" }
        return ResponseEntity.badRequest().body(BaseResponse.fail(ErrorCode.INVALID_REQUEST.code, message))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException): ResponseEntity<BaseResponse<Any>> =
        ResponseEntity.badRequest().body(BaseResponse.fail(ErrorCode.INVALID_REQUEST.code, "요청 본문을 해석할 수 없습니다"))

    @ExceptionHandler(BusinessException::class)
    fun handleKbap(e: BusinessException): ResponseEntity<BaseResponse<Any>> {
        val status = HttpStatus.resolve(e.errorCode.status) ?: HttpStatus.INTERNAL_SERVER_ERROR
        if (status.is5xxServerError) {
            log.error("business exception (server): {} (status={})", e.errorCode.message, status.value(), e)
        } else {
            log.warn("business exception (client): {} (status={})", e.errorCode.message, status.value())
        }
        return ResponseEntity.status(status).body(BaseResponse.fail(e.errorCode.code, e.errorCode.message, e.payload))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<BaseResponse<Any>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(BaseResponse.fail(ErrorCode.INVALID_REQUEST.code, e.message ?: "잘못된 요청입니다"))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<BaseResponse<Any>> =
        ResponseEntity.badRequest().body(BaseResponse.fail(ErrorCode.INVALID_REQUEST.code, "잘못된 요청입니다"))
}
