package com.kbap.app.api.common

import com.kbap.core.error.KbapException
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
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<BaseResponse<Nothing>> {
        val message = e.bindingResult.allErrors
            .joinToString("; ") { error ->
                val field = (error as? org.springframework.validation.FieldError)?.field ?: error.objectName
                "$field: ${error.defaultMessage}"
            }
            .ifBlank { "잘못된 요청입니다" }
        return ResponseEntity.badRequest().body(BaseResponse.fail(message))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.badRequest().body(BaseResponse.fail("요청 본문을 해석할 수 없습니다"))

    @ExceptionHandler(KbapException::class)
    fun handleKbap(e: KbapException): ResponseEntity<BaseResponse<Nothing>> {
        val status = HttpStatus.resolve(e.errorCode.status) ?: HttpStatus.INTERNAL_SERVER_ERROR
        if (status.is5xxServerError) {
            log.error("business exception (server): {} (status={})", e.errorCode.message, status.value(), e)
        } else {
            log.warn("business exception (client): {} (status={})", e.errorCode.message, status.value())
        }
        return ResponseEntity.status(status).body(BaseResponse.fail(e.errorCode.message))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(BaseResponse.fail(e.message ?: "잘못된 요청입니다"))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.badRequest().body(BaseResponse.fail("잘못된 요청입니다"))
}
