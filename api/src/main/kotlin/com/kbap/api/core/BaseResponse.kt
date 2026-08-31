package com.kbap.api.core

data class BaseResponse<T>(
    val success: Boolean,
    val payload: T? = null,
    val message: String? = null,
    val code: String? = null,
) {
    companion object {
        fun <T> ok(payload: T): BaseResponse<T> = BaseResponse(success = true, payload = payload)

        fun fail(code: String, message: String, payload: Any? = null): BaseResponse<Any> =
            BaseResponse(success = false, payload = payload, message = message, code = code)
    }
}
