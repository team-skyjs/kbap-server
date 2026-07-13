package com.kbap.application.auth

import com.kbap.core.error.KbapException

open class AuthException(
    errorCode: AuthErrorCode,
) : KbapException(errorCode)
