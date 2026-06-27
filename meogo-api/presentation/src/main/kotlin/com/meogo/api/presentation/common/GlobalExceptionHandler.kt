package com.meogo.api.presentation.common

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Nothing>> {
        val message = e.bindingResult.allErrors
            .joinToString("; ") { error ->
                val field = (error as? org.springframework.validation.FieldError)?.field ?: error.objectName
                "$field: ${error.defaultMessage}"
            }
            .ifBlank { "잘못된 요청입니다" }
        return ResponseEntity.badRequest().body(ApiResponse.fail(message))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.badRequest().body(ApiResponse.fail("요청 본문을 해석할 수 없습니다"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(e.message ?: "잘못된 요청입니다"))
}
