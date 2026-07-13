package com.kbap.domain.member

import com.kbap.core.error.KbapException

open class MemberException(
    errorCode: MemberErrorCode,
) : KbapException(errorCode)
