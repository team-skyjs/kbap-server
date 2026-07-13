package com.meogo.application.member

import com.meogo.core.error.MeogoException

open class OnboardingException(
    errorCode: OnboardingErrorCode,
) : MeogoException(errorCode)
