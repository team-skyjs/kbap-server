package com.meogo.domain.food

import com.meogo.core.error.MeogoException

open class FoodException(
    errorCode: FoodErrorCode,
) : MeogoException(errorCode)
