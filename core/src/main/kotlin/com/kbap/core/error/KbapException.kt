package com.kbap.core.error

open class KbapException(
    val errorCode: ErrorCode,
) : RuntimeException(errorCode.message)
