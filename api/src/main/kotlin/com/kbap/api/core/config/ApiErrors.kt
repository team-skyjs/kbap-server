package com.kbap.api.core.config

import com.kbap.common.core.error.ErrorCode

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ApiErrors(vararg val codes: ErrorCode)
