package com.kbap.core.error

abstract class KbapException(
    val errorCode: ErrorCode,
) : RuntimeException(errorCode.message)
