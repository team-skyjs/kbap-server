package com.meogo.core.kernel.lang

import com.meogo.core.kernel.error.MeogoException

abstract class LanguageException(
    errorCode: LanguageErrorCode,
) : MeogoException(errorCode)
