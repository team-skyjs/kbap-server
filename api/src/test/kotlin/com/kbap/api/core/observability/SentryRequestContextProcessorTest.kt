package com.kbap.api.core.observability

import com.kbap.api.core.logging.RequestLoggingFilter
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.sentry.Hint
import io.sentry.SentryEvent
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class SentryRequestContextProcessorTest : BehaviorSpec({
    val processor = SentryRequestContextProcessor()

    fun processed(throwable: Throwable?): SentryEvent =
        processor.process(SentryEvent(throwable), Hint())!!

    afterTest { MDC.clear() }

    given("핸들러 예외로 만들어진 Sentry 이벤트") {
        `when`("4xx 에러 코드의 BusinessException 이면") {
            val event = processed(BusinessException(ErrorCode.FOOD_NOT_FOUND))
            then("http.status·error.code 태그와 에러 코드 핑거프린트가 붙는다") {
                event.getTag("http.status") shouldBe "400"
                event.getTag("error.code") shouldBe "FOOD-001"
                event.fingerprints shouldBe listOf("business", "FOOD-001")
            }
        }

        `when`("5xx 에러 코드의 BusinessException 이면") {
            val event = processed(BusinessException(ErrorCode.SCAN_VISION_UNAVAILABLE))
            then("같은 규칙으로 503 과 코드가 붙는다") {
                event.getTag("http.status") shouldBe "503"
                event.getTag("error.code") shouldBe "SCAN-006"
                event.fingerprints shouldBe listOf("business", "SCAN-006")
            }
        }

        `when`("Spring ErrorResponse 예외이면") {
            val event = processed(ResponseStatusException(HttpStatus.NOT_FOUND))
            then("그 상태 코드만 붙고 핑거프린트는 SDK 기본이다") {
                event.getTag("http.status") shouldBe "404"
                event.getTag("error.code").shouldBeNull()
                event.fingerprints.shouldBeNull()
            }
        }

        `when`("그 외 예외이면") {
            val event = processed(RuntimeException("boom"))
            then("500 으로 태그되고 에러 코드는 없다") {
                event.getTag("http.status") shouldBe "500"
                event.getTag("error.code").shouldBeNull()
                event.fingerprints.shouldBeNull()
            }
        }
    }

    given("요청 MDC") {
        `when`("requestId·memberId 가 있으면") {
            MDC.put(RequestLoggingFilter.REQUEST_ID_KEY, "req-1")
            MDC.put(RequestLoggingFilter.MEMBER_ID_KEY, "42")
            val event = processed(RuntimeException("boom"))
            then("두 값이 태그가 된다") {
                event.getTag("requestId") shouldBe "req-1"
                event.getTag("memberId") shouldBe "42"
            }
        }

        `when`("게스트라 memberId 가 없으면") {
            MDC.put(RequestLoggingFilter.REQUEST_ID_KEY, "req-2")
            val event = processed(RuntimeException("boom"))
            then("requestId 만 붙는다") {
                event.getTag("requestId") shouldBe "req-2"
                event.getTag("memberId").shouldBeNull()
            }
        }
    }

    given("예외 없는 로그 이벤트") {
        `when`("처리하면") {
            val event = processed(null)
            then("http.status 를 붙이지 않는다") {
                event.getTag("http.status").shouldBeNull()
            }
        }
    }
})
