package com.meogo.domain.member

import com.meogo.core.error.MeogoException

open class MemberException(
    errorCode: MemberErrorCode,
) : MeogoException(errorCode)
