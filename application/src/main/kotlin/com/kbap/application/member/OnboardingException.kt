package com.kbap.application.member

import com.kbap.core.error.KbapException

open class OnboardingException(
    errorCode: OnboardingErrorCode,
) : KbapException(errorCode)
