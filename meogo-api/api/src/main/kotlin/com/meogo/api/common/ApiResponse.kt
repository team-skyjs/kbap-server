package com.meogo.api.common

/**
 * 모든 web 응답 공통 봉투(고정 규약, CLAUDE.md "API 응답 규약").
 * 성공: [ok] — success=true·data 페이로드·message=null.
 * 실패: [fail] — success=false·data=null·message 사유.
 */
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val message: String? = null,
) {
    companion object {
        fun <T> ok(data: T): ApiResponse<T> = ApiResponse(success = true, data = data)
        fun fail(message: String): ApiResponse<Nothing> = ApiResponse(success = false, message = message)
    }
}
