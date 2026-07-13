package com.meogo.core.lang

import com.meogo.core.error.MeogoException

open class LanguageException(
    errorCode: LanguageErrorCode,
) : MeogoException(errorCode)
