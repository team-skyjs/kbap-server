package com.kbap.api.core.observability

import com.kbap.api.core.logging.MASKED_QUERY_PARAMS
import com.kbap.api.core.logging.RequestLoggingFilter
import com.kbap.api.core.logging.maskQuery
import com.kbap.common.core.error.BusinessException
import io.sentry.EventProcessor
import io.sentry.Hint
import io.sentry.SentryEvent
import jakarta.persistence.OptimisticLockException
import org.slf4j.MDC
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.stereotype.Component
import org.springframework.web.ErrorResponse
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@Component
class SentryRequestContextProcessor : EventProcessor {

    override fun process(event: SentryEvent, hint: Hint): SentryEvent {
        listOf(RequestLoggingFilter.REQUEST_ID_KEY, RequestLoggingFilter.MEMBER_ID_KEY)
            .forEach { key -> MDC.get(key)?.let { event.setTag(key, it) } }
        event.request?.let { request ->
            request.headers = request.headers?.filterKeys { !it.equals(HttpHeaders.AUTHORIZATION, ignoreCase = true) }
            request.queryString = maskQuery(request.queryString, MASKED_QUERY_PARAMS)
        }
        when (val throwable = event.throwable) {
            null -> Unit
            is BusinessException -> {
                event.setTag(HTTP_STATUS_TAG, throwable.errorCode.status.toString())
                event.setTag("error.code", throwable.errorCode.code)
                event.fingerprints = listOf("business", throwable.errorCode.code)
            }
            is ErrorResponse -> event.setTag(HTTP_STATUS_TAG, throwable.statusCode.value().toString())
            is IllegalArgumentException, is HttpMessageNotReadableException, is MethodArgumentTypeMismatchException ->
                event.setTag(HTTP_STATUS_TAG, "400")
            else -> event.setTag(HTTP_STATUS_TAG, if (hasOptimisticConflictCause(throwable)) "409" else "500")
        }
        return event
    }

    private fun hasOptimisticConflictCause(throwable: Throwable): Boolean =
        generateSequence(throwable) { it.cause }
            .any { it is OptimisticLockingFailureException || it is OptimisticLockException }

    private companion object {
        const val HTTP_STATUS_TAG = "http.status"
    }
}
