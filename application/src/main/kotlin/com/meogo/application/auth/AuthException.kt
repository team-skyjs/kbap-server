package com.meogo.application.auth

import com.meogo.core.error.MeogoException

open class AuthException(
    errorCode: AuthErrorCode,
) : MeogoException(errorCode)
