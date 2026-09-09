package com.kbap.api.core.observability

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.sentry.Hint
import io.sentry.SentryEvent
import java.io.IOException
import org.apache.catalina.connector.ClientAbortException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpMethod
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.servlet.resource.NoResourceFoundException

class SentryRequestContextProcessorTest : BehaviorSpec({
    val processor = SentryRequestContextProcessor()
    fun process(throwable: Throwable?) = processor.process(SentryEvent(throwable), Hint())

    given("Sentry 이벤트 프로세서") {
        `when`("4xx BusinessException 이면") {
            then("이벤트를 버린다") {
                process(BusinessException(ErrorCode.INVALID_ACCESS_TOKEN)).shouldBeNull()
            }
        }

        `when`("409 BusinessException(중복 등록 등 정상 충돌) 이면") {
            then("이벤트를 버린다") {
                process(BusinessException(ErrorCode.CONFLICT)).shouldBeNull()
            }
        }

        `when`("5xx BusinessException 이면") {
            then("http.status·error.code 태그를 달아 보낸다") {
                val event = process(BusinessException(ErrorCode.SOCIAL_ACCOUNT_DELETE_FAILED)).shouldNotBeNull()
                event.getTag("http.status") shouldBe "500"
                event.getTag("error.code") shouldBe "AUTH-007"
            }
        }

        `when`("정적 리소스 404(NoResourceFoundException) 이면") {
            then("이벤트를 버린다") {
                process(NoResourceFoundException(HttpMethod.GET, "/", "/")).shouldBeNull()
            }
        }

        `when`("바인딩 실패(IllegalArgumentException, 400) 이면") {
            then("이벤트를 버린다") {
                process(IllegalArgumentException("bad")).shouldBeNull()
            }
        }

        `when`("예상 못 한 예외(500) 이면") {
            then("http.status=500 으로 보낸다") {
                process(RuntimeException("boom")).shouldNotBeNull().getTag("http.status") shouldBe "500"
            }
        }

        `when`("낙관적 락 충돌(409) 이 cause 에 있으면") {
            then("http.status=409 로 보낸다") {
                val throwable = RuntimeException("wrap", OptimisticLockingFailureException("conflict"))
                process(throwable).shouldNotBeNull().getTag("http.status") shouldBe "409"
            }
        }

        `when`("cause 체인에 ClientAbortException 이 있으면") {
            then("이벤트를 버린다") {
                process(RuntimeException("wrap", ClientAbortException(IOException("Broken pipe")))).shouldBeNull()
            }
        }

        `when`("cause 체인에 AsyncRequestNotUsableException 이 있으면") {
            then("이벤트를 버린다") {
                process(AsyncRequestNotUsableException("gone")).shouldBeNull()
            }
        }

        `when`("아웃바운드 호출의 IOException(Connection reset·Broken pipe 메시지) 이면") {
            then("클라이언트 끊김으로 보지 않고 500 으로 보낸다") {
                process(RuntimeException("wrap", IOException("Connection reset by peer"))).shouldNotBeNull().getTag("http.status") shouldBe "500"
                process(RuntimeException("wrap", IOException("Broken pipe"))).shouldNotBeNull().getTag("http.status") shouldBe "500"
            }
        }

        `when`("throwable 이 없는 로그 이벤트면") {
            then("그대로 보낸다") {
                process(null).shouldNotBeNull()
            }
        }
    }
})
