package com.kbap.core.lang

import com.kbap.core.error.KbapException

open class LanguageException(
    errorCode: LanguageErrorCode,
) : KbapException(errorCode)
