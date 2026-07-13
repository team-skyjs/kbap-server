package com.kbap.domain.food

import com.kbap.core.error.KbapException

open class FoodException(
    errorCode: FoodErrorCode,
) : KbapException(errorCode)
