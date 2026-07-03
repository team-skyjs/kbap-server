package com.meogo.core.food

import com.meogo.core.kernel.error.MeogoException

abstract class FoodException(
    errorCode: FoodErrorCode,
) : MeogoException(errorCode)
