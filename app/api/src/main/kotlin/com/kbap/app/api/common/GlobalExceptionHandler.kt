package com.kbap.app.api.common

import com.kbap.core.error.BusinessException
import com.kbap.core.error.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        e: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<BaseResponse<Any>> {
        val message = e.bindingResult.allErrors
            .joinToString("; ") { error ->
                val field = (error as? org.springframework.validation.FieldError)?.field ?: error.objectName
                "$field: ${error.defaultMessage}"
            }
            .ifBlank { "잘못된 요청입니다" }
        logFailure(e, ErrorCode.INVALID_REQUEST.code, HttpStatus.BAD_REQUEST, request)
        return ResponseEntity.badRequest().body(BaseResponse.fail(ErrorCode.INVALID_REQUEST.code, message))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(
        e: HttpMessageNotReadableException,
        request: HttpServletRequest,
    ): ResponseEntity<BaseResponse<Any>> {
        logFailure(e, ErrorCode.INVALID_REQUEST.code, HttpStatus.BAD_REQUEST, request)
        return ResponseEntity.badRequest().body(BaseResponse.fail(ErrorCode.INVALID_REQUEST.code, "요청 본문을 해석할 수 없습니다"))
    }

    @ExceptionHandler(BusinessException::class)
    fun handleKbap(e: BusinessException, request: HttpServletRequest): ResponseEntity<BaseResponse<Any>> {
        val status = HttpStatus.resolve(e.errorCode.status) ?: HttpStatus.INTERNAL_SERVER_ERROR
        logFailure(e, e.errorCode.code, status, request)
        return ResponseEntity.status(status).body(BaseResponse.fail(e.errorCode.code, e.errorCode.message, e.payload))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(
        e: IllegalArgumentException,
        request: HttpServletRequest,
    ): ResponseEntity<BaseResponse<Any>> {
        logFailure(e, ErrorCode.INVALID_REQUEST.code, HttpStatus.BAD_REQUEST, request)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(BaseResponse.fail(ErrorCode.INVALID_REQUEST.code, e.message ?: "잘못된 요청입니다"))
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(
        e: MethodArgumentTypeMismatchException,
        request: HttpServletRequest,
    ): ResponseEntity<BaseResponse<Any>> {
        logFailure(e, ErrorCode.INVALID_REQUEST.code, HttpStatus.BAD_REQUEST, request)
        return ResponseEntity.badRequest().body(BaseResponse.fail(ErrorCode.INVALID_REQUEST.code, "잘못된 요청입니다"))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception, request: HttpServletRequest): ResponseEntity<BaseResponse<Any>> {
        // 404·405·415 등 스프링 MVC 예외는 자기 상태 코드를 안다(ErrorResponse) —
        // 500 으로 뭉개면 클라이언트 잘못이 서버 장애로 둔갑하므로 원래 상태를 보존한다.
        if (e is ErrorResponse) {
            val status = HttpStatus.resolve(e.statusCode.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR
            logFailure(e, ErrorCode.INVALID_REQUEST.code, status, request)
            return ResponseEntity.status(status)
                .body(BaseResponse.fail(ErrorCode.INVALID_REQUEST.code, ErrorCode.INVALID_REQUEST.message))
        }
        logFailure(e, ErrorCode.INTERNAL_SERVER_ERROR.code, HttpStatus.INTERNAL_SERVER_ERROR, request)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(BaseResponse.fail(ErrorCode.INTERNAL_SERVER_ERROR.code, ErrorCode.INTERNAL_SERVER_ERROR.message))
    }

    private fun logFailure(e: Exception, errorCode: String, status: HttpStatus, request: HttpServletRequest) {
        val builder = if (status.is5xxServerError) log.atError().setCause(e) else log.atWarn()
        builder
            .addKeyValue("exception", e.javaClass.simpleName)
            .addKeyValue("errorCode", errorCode)
            .addKeyValue("status", status.value())
            .addKeyValue("uri", request.requestURI)
            .log(
                "request failed: {} {} {} {}",
                e.javaClass.simpleName,
                errorCode,
                status.value(),
                request.requestURI,
            )
    }
}
