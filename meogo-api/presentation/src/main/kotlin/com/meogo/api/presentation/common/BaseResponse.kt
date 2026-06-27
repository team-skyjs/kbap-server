package com.meogo.api.presentation.common

data class BaseResponse<T>(
    val success: Boolean,
    val payload: T? = null,
    val message: String? = null,
) {
    companion object {
        fun <T> ok(payload: T): BaseResponse<T> = BaseResponse(success = true, payload = payload)
        fun fail(message: String): BaseResponse<Nothing> = BaseResponse(success = false, message = message)
    }
}
