package com.meogo.core.member

import com.meogo.core.kernel.error.MeogoException

open class MemberException(
    errorCode: MemberErrorCode,
) : MeogoException(errorCode)
