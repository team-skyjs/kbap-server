package com.kbap.api.core.observability

import com.kbap.api.core.GlobalExceptionHandler
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import jakarta.persistence.LockTimeoutException
import jakarta.persistence.OptimisticLockException
import jakarta.persistence.PessimisticLockException
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.sql.SQLException

class SentryRequestContextProcessorTest : BehaviorSpec({

    @RestController
    class Thrower {
        lateinit var next: Exception

        @GetMapping("/throw")
        fun raise(): Nothing = throw next
    }

    val thrower = Thrower()
    val mockMvc = MockMvcBuilders.standaloneSetup(thrower).setControllerAdvice(GlobalExceptionHandler()).build()
    val processor = SentryRequestContextProcessor()

    fun responseStatusOf(e: Exception): Int {
        thrower.next = e
        return mockMvc.get("/throw").andReturn().response.status
    }

    fun eventOf(e: Exception): SentryEvent? = processor.process(SentryEvent(e).apply { level = SentryLevel.FATAL }, Hint())

    val deadlock = CannotAcquireLockException("deadlock", SQLException("Deadlock found when trying to get lock", "40001", 1213))
    val lockWaitTimeout = CannotAcquireLockException("timeout", SQLException("Lock wait timeout exceeded", "HY000", 1205))
    val lockConflicts = mapOf(
        "교착 희생자" to deadlock,
        "잠금 대기 초과" to lockWaitTimeout,
        "비관 잠금 실패" to PessimisticLockingFailureException("pessimistic"),
        "감싸인 JPA 비관 잠금 예외" to IllegalStateException(PessimisticLockException()),
        "감싸인 JPA 잠금 대기 초과" to IllegalStateException(LockTimeoutException()),
        "낙관 충돌" to OptimisticLockingFailureException("optimistic"),
        "감싸인 JPA 낙관 충돌" to IllegalStateException(OptimisticLockException()),
    )

    given("잠금 충돌로 끝난 요청의 Sentry 이벤트") {
        `when`("http.status 태그를 붙이면") {
            then("실제 응답 상태(409)와 같다 — 응답과 태그가 같은 분류에서 나온다") {
                lockConflicts.forEach { (name, exception) ->
                    withClue(name) {
                        responseStatusOf(exception) shouldBe 409
                        eventOf(exception)?.getTag("http.status") shouldBe "409"
                    }
                }
            }
        }

        `when`("심각도를 정하면") {
            then("비관 잠금 실패(교착 희생자 포함)는 error 다 — 교착은 재시도로 회복되지만 구조 결함의 신호라 알림을 유지한다") {
                eventOf(deadlock)?.level shouldBe SentryLevel.ERROR
                eventOf(lockWaitTimeout)?.level shouldBe SentryLevel.ERROR
                eventOf(PessimisticLockingFailureException("pessimistic"))?.level shouldBe SentryLevel.ERROR
                eventOf(IllegalStateException(PessimisticLockException()))?.level shouldBe SentryLevel.ERROR
                eventOf(IllegalStateException(LockTimeoutException()))?.level shouldBe SentryLevel.ERROR
            }

            then("낙관 충돌은 warning 이다 — 동시 수정의 정상적인 결말이다") {
                eventOf(OptimisticLockingFailureException("optimistic"))?.level shouldBe SentryLevel.WARNING
                eventOf(IllegalStateException(OptimisticLockException()))?.level shouldBe SentryLevel.WARNING
            }
        }
    }

    given("잠금 충돌이 아닌 요청의 Sentry 이벤트") {
        `when`("처리되지 않은 서버 오류면") {
            then("태그는 응답과 같은 500 이고 심각도는 그대로다") {
                val exception = IllegalStateException("boom")

                responseStatusOf(exception) shouldBe 500
                eventOf(exception)?.getTag("http.status") shouldBe "500"
                eventOf(exception)?.level shouldBe SentryLevel.FATAL
            }
        }

        `when`("클라이언트 잘못(4xx)이면") {
            then("이벤트를 보내지 않는다") {
                eventOf(BusinessException(ErrorCode.INVALID_REQUEST)) shouldBe null
            }
        }
    }
})
