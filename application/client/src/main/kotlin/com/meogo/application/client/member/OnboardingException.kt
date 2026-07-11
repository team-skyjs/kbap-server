package com.meogo.application.client.member

import com.meogo.core.kernel.error.MeogoException

open class OnboardingException(
    errorCode: OnboardingErrorCode,
) : MeogoException(errorCode)
