package com.kbap.api.core.observability

import com.kbap.api.core.logging.MASKED_QUERY_PARAMS
import com.kbap.api.core.logging.RequestLoggingFilter
import com.kbap.api.core.logging.maskQuery
import com.kbap.common.core.error.BusinessException
import io.sentry.EventProcessor
import io.sentry.Hint
import io.sentry.SentryEvent
import jakarta.persistence.OptimisticLockException
import java.io.IOException
import org.apache.catalina.connector.ClientAbortException
import org.slf4j.MDC
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpHeaders
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
        val status = httpStatusOf(throwable)
        if (status in 400..499 && !hasOptimisticConflictCause(throwable)) return null
        event.setTag(HTTP_STATUS_TAG, status.toString())
        if (throwable is BusinessException) {
            event.setTag("error.code", throwable.errorCode.code)
            event.fingerprints = listOf("business", throwable.errorCode.code)
        }
        return event
    }

    private fun httpStatusOf(throwable: Throwable): Int =
        when (throwable) {
            is BusinessException -> throwable.errorCode.status
            is ErrorResponse -> throwable.statusCode.value()
            is IllegalArgumentException, is HttpMessageNotReadableException, is MethodArgumentTypeMismatchException -> 400
            else -> if (hasOptimisticConflictCause(throwable)) 409 else 500
        }

    private fun isClientAbort(throwable: Throwable): Boolean =
        causeChain(throwable).any {
            it is ClientAbortException ||
                it is AsyncRequestNotUsableException ||
                (it is IOException && CLIENT_ABORT_MESSAGES.any { marker -> it.message?.contains(marker) == true })
        }

    private fun hasOptimisticConflictCause(throwable: Throwable): Boolean =
        causeChain(throwable).any { it is OptimisticLockingFailureException || it is OptimisticLockException }

    private fun causeChain(throwable: Throwable): Sequence<Throwable> = generateSequence(throwable) { it.cause }

    private companion object {
        const val HTTP_STATUS_TAG = "http.status"
        val CLIENT_ABORT_MESSAGES = listOf("Broken pipe", "Connection reset")
    }
}
