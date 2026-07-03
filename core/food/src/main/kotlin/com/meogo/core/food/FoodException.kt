package com.meogo.core.food

import com.meogo.core.kernel.error.MeogoException

open class FoodException(
    errorCode: FoodErrorCode,
) : MeogoException(errorCode)
