package com.kbap.api.core.observability

import com.kbap.api.core.logging.RequestLoggingFilter
import com.kbap.common.core.error.BusinessException
import io.sentry.EventProcessor
import io.sentry.Hint
import io.sentry.SentryEvent
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.ErrorResponse

@Component
class SentryRequestContextProcessor : EventProcessor {

    override fun process(event: SentryEvent, hint: Hint): SentryEvent {
        listOf(RequestLoggingFilter.REQUEST_ID_KEY, RequestLoggingFilter.MEMBER_ID_KEY)
            .forEach { key -> MDC.get(key)?.let { event.setTag(key, it) } }
        when (val throwable = event.throwable) {
            null -> Unit
            is BusinessException -> {
                event.setTag(HTTP_STATUS_TAG, throwable.errorCode.status.toString())
                event.setTag("error.code", throwable.errorCode.code)
                event.fingerprints = listOf("business", throwable.errorCode.code)
            }
            is ErrorResponse -> event.setTag(HTTP_STATUS_TAG, throwable.statusCode.value().toString())
            else -> event.setTag(HTTP_STATUS_TAG, "500")
        }
        return event
    }

    private companion object {
        const val HTTP_STATUS_TAG = "http.status"
    }
}
