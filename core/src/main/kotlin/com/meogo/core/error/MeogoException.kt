package com.meogo.core.error

abstract class MeogoException(
    val errorCode: ErrorCode,
) : RuntimeException(errorCode.message)
