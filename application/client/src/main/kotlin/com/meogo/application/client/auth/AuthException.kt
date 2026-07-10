package com.meogo.application.client.auth

import com.meogo.core.kernel.error.MeogoException

open class AuthException(
    errorCode: AuthErrorCode,
) : MeogoException(errorCode)
