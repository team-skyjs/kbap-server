package com.kbap.api.core.observability

import com.kbap.api.core.LockConflict
import com.kbap.api.core.logging.MASKED_QUERY_PARAMS
import com.kbap.api.core.logging.RequestLoggingFilter
import com.kbap.api.core.logging.maskQuery
import com.kbap.common.core.error.BusinessException
import io.sentry.EventProcessor
import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import org.apache.catalina.connector.ClientAbortException
import org.slf4j.MDC
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.stereotype.Component
import org.springframework.web.ErrorResponse
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@Component
class SentryRequestContextProcessor : EventProcessor {

    override fun process(event: SentryEvent, hint: Hint): SentryEvent? {
        listOf(RequestLoggingFilter.REQUEST_ID_KEY, RequestLoggingFilter.MEMBER_ID_KEY)
            .forEach { key -> MDC.get(key)?.let { event.setTag(key, it) } }
        event.request?.let { request ->
            request.headers = request.headers?.filterKeys { !it.equals(HttpHeaders.AUTHORIZATION, ignoreCase = true) }
            request.queryString = maskQuery(request.queryString, MASKED_QUERY_PARAMS)
        }
        val throwable = event.throwable ?: return event
        if (isClientAbort(throwable)) return null
        if (throwable is BusinessException && throwable.expected) return null
        val lockConflict = LockConflict.of(throwable)
        val status = if (lockConflict != null) HttpStatus.CONFLICT.value() else httpStatusOf(throwable)
        if (status in 400..499 && lockConflict == null) return null
        event.setTag(HTTP_STATUS_TAG, status.toString())
        if (lockConflict != null) {
            event.setTag(LOCK_CONFLICT_TAG, lockConflict.name.lowercase())
            event.level = if (lockConflict.severe) SentryLevel.ERROR else SentryLevel.WARNING
        }
        if (throwable is BusinessException) {
            event.setTag("error.code", throwable.errorCode.code)
            event.fingerprints = listOf("business", throwable.errorCode.code)
            event.level = SentryLevel.ERROR
        }
        return event
    }

    private fun httpStatusOf(throwable: Throwable): Int =
        when (throwable) {
            is BusinessException -> throwable.errorCode.status
            is ErrorResponse -> throwable.statusCode.value()
            is IllegalArgumentException, is HttpMessageNotReadableException, is MethodArgumentTypeMismatchException -> 400
            else -> 500
        }

    private fun isClientAbort(throwable: Throwable): Boolean =
        causeChain(throwable).any { it is ClientAbortException || it is AsyncRequestNotUsableException }

    private fun causeChain(throwable: Throwable): Sequence<Throwable> = generateSequence(throwable) { it.cause }

    private companion object {
        const val HTTP_STATUS_TAG = "http.status"
        const val LOCK_CONFLICT_TAG = "lock.conflict"
    }
}
