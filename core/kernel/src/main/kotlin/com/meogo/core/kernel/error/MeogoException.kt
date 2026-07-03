package com.meogo.core.kernel.error

abstract class MeogoException(
    val errorCode: ErrorCode,
) : RuntimeException(errorCode.message)
