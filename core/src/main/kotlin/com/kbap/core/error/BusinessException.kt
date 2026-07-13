package com.kbap.core.error

open class BusinessException(
    val errorCode: ErrorCode,
) : RuntimeException(errorCode.message)
