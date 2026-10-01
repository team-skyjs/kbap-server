package com.kbap.api.core.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import org.slf4j.LoggerFactory
import org.springframework.core.MethodParameter
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.http.MockHttpInputMessage
import org.springframework.test.web.servlet.get
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.context.MessageSourceResolvable
import org.springframework.validation.method.MethodValidationResult
import org.springframework.validation.method.ParameterValidationResult
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import java.sql.SQLException

class SentryRequestContextProcessorTest : BehaviorSpec({

    @RestController
    class Thrower {
        lateinit var next: Exception

        @GetMapping("/throw")
        fun raise(): Nothing = throw next
    }

    data class Case(val name: String, val exception: Exception, val status: Int, val logLevel: Level, val sentryLevel: SentryLevel?)

    val thrower = Thrower()
    val mockMvc = MockMvcBuilders.standaloneSetup(thrower).setControllerAdvice(GlobalExceptionHandler()).build()
    val processor = SentryRequestContextProcessor()
    val handlerLogger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as Logger

    fun responseOf(e: Exception): Pair<Int, Level> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        handlerLogger.addAppender(appender)
        try {
            thrower.next = e
            val status = mockMvc.get("/throw").andReturn().response.status
            return status to appender.list.last().level
        } finally {
            handlerLogger.detachAppender(appender)
        }
    }

    fun responseCodeOf(e: Exception): Pair<Int, String> {
        thrower.next = e
        val response = mockMvc.get("/throw").andReturn().response
        return response.status to jacksonObjectMapper().readTree(response.getContentAsString(Charsets.UTF_8)).path("code").asText()
    }

    fun eventOf(e: Exception): SentryEvent? = processor.process(SentryEvent(e).apply { level = SentryLevel.FATAL }, Hint())

    fun verify(cases: List<Case>) = cases.forEach { case ->
        withClue(case.name) {
            val (status, logLevel) = responseOf(case.exception)
            val event = eventOf(case.exception)

            status shouldBe case.status
            logLevel shouldBe case.logLevel
            event?.level shouldBe case.sentryLevel
            event?.getTag("http.status") shouldBe case.sentryLevel?.let { case.status.toString() }
        }
    }

    val deadlock = CannotAcquireLockException("deadlock", SQLException("Deadlock found when trying to get lock", "40001", 1213))
    val lockWaitTimeout = CannotAcquireLockException("timeout", SQLException("Lock wait timeout exceeded", "HY000", 1205))
    val raise = MethodParameter(Thrower::class.java.getMethod("raise"), -1)

    given("잠금 충돌로 끝난 요청") {
        `when`("종류별로 응답 상태·응답 로그 수준·Sentry 이벤트를 나란히 놓으면") {
            then("셋 다 409 이고, 교착과 잠금 대기는 ERROR/error, 낙관 충돌은 WARN/warning 이다 — 교착은 재시도로 회복되지만 잠금 순서 결함의 신호다") {
                verify(
                    listOf(
                        Case("교착 희생자", deadlock, 409, Level.ERROR, SentryLevel.ERROR),
                        Case("잠금 대기 초과", lockWaitTimeout, 409, Level.ERROR, SentryLevel.ERROR),
                        Case("비관 잠금 실패", PessimisticLockingFailureException("pessimistic"), 409, Level.ERROR, SentryLevel.ERROR),
                        Case("감싸인 JPA 비관 잠금 예외", IllegalStateException(PessimisticLockException()), 409, Level.ERROR, SentryLevel.ERROR),
                        Case("감싸인 JPA 잠금 대기 초과", IllegalStateException(LockTimeoutException()), 409, Level.ERROR, SentryLevel.ERROR),
                        Case("낙관 충돌", OptimisticLockingFailureException("optimistic"), 409, Level.WARN, SentryLevel.WARNING),
                        Case("감싸인 JPA 낙관 충돌", IllegalStateException(OptimisticLockException()), 409, Level.WARN, SentryLevel.WARNING),
                    ),
                )
            }

            then("다른 예외에 감싸여 있어도 응답과 같은 쪽을 따른다 — 응답이 409 면 태그도 409, 응답이 감싼 예외의 상태면 이벤트도 그쪽 규칙이다") {
                verify(
                    listOf(
                        Case("잠금 실패를 감싼 스프링 MVC 예외", ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "wrapped", lockWaitTimeout), 409, Level.ERROR, SentryLevel.ERROR),
                        Case("교착을 감싼 스프링 MVC 예외", ResponseStatusException(HttpStatus.BAD_REQUEST, "wrapped", deadlock), 409, Level.ERROR, SentryLevel.ERROR),
                        Case("교착을 감싼 잘못된 인자", IllegalArgumentException("wrapped", deadlock), 400, Level.WARN, null),
                    ),
                )
            }

            then("이벤트에 잠금 충돌 종류 태그가 붙는다 — 알림 규칙이 교착만 골라 잡을 수 있다") {
                eventOf(deadlock)?.getTag("lock.conflict") shouldBe "deadlock"
                eventOf(lockWaitTimeout)?.getTag("lock.conflict") shouldBe "lock_wait"
                eventOf(OptimisticLockingFailureException("optimistic"))?.getTag("lock.conflict") shouldBe "optimistic"
            }
        }
    }

    given("GlobalExceptionHandler 가 받는 예외 타입 전부") {
        val handledTypes = GlobalExceptionHandler::class.java.declaredMethods
            .flatMap { method -> method.getAnnotation(ExceptionHandler::class.java)?.value?.map { it.java } ?: emptyList() }
            .toSet()
        val noViolations = object : MethodValidationResult {
            override fun getTarget(): Any = thrower
            override fun getMethod(): java.lang.reflect.Method = raise.method!!
            override fun isForReturnValue(): Boolean = false
            override fun getParameterValidationResults(): List<ParameterValidationResult> = emptyList()
            override fun getCrossParameterValidationResults(): List<MessageSourceResolvable> = emptyList()
        }
        val samples: Map<Class<out Throwable>, (Throwable?) -> Exception> = mapOf(
            MethodArgumentNotValidException::class.java to { wrapped ->
                object : MethodArgumentNotValidException(raise, BeanPropertyBindingResult(Any(), "target")) {
                    override val cause: Throwable? get() = wrapped
                }
            },
            HandlerMethodValidationException::class.java to { wrapped ->
                object : HandlerMethodValidationException(noViolations) {
                    override val cause: Throwable? get() = wrapped
                }
            },
            HttpMessageNotReadableException::class.java to { wrapped -> HttpMessageNotReadableException("bad", wrapped, MockHttpInputMessage(ByteArray(0))) },
            BusinessException::class.java to { wrapped ->
                object : BusinessException(ErrorCode.INVALID_REQUEST) {
                    override val cause: Throwable? get() = wrapped
                }
            },
            IllegalArgumentException::class.java to { wrapped -> IllegalArgumentException("bad", wrapped) },
            MethodArgumentTypeMismatchException::class.java to { wrapped -> MethodArgumentTypeMismatchException("x", Long::class.java, "id", raise, wrapped) },
            OptimisticLockingFailureException::class.java to { wrapped -> OptimisticLockingFailureException("optimistic", wrapped) },
            PessimisticLockingFailureException::class.java to { wrapped -> PessimisticLockingFailureException("pessimistic", wrapped) },
            Exception::class.java to { wrapped -> IllegalStateException("boom", wrapped) },
        )

        `when`("핸들러에 @ExceptionHandler 가 더해지면") {
            then("이 표에도 표본이 있어야 한다 — 새 핸들러의 예외는 아래 대조를 거치지 않고는 들어오지 못한다") {
                samples.keys shouldBe handledTypes
            }
        }

        `when`("각 타입을 그대로, 그리고 원인 사슬에 교착·낙관 충돌을 품은 채로 던지면") {
            then("Sentry 판정이 실제 응답을 따른다 — 5xx 와 잠금 충돌 409(COMMON-004)만 보내고, 태그는 응답 상태와 같다") {
                samples.forEach { (type, sample) ->
                    listOf(null, deadlock, OptimisticLockingFailureException("optimistic")).forEach { wrapped ->
                        withClue("${type.simpleName} (원인: ${wrapped?.javaClass?.simpleName})") {
                            val exception = sample(wrapped)
                            val (status, code) = responseCodeOf(exception)
                            val event = eventOf(exception)

                            (event != null) shouldBe (status >= 500 || (status == 409 && code == ErrorCode.CONFLICT.code))
                            event?.getTag("http.status") shouldBe event?.let { status.toString() }
                        }
                    }
                }
            }
        }
    }

    given("잠금 충돌이 아닌 요청") {
        `when`("클라이언트 잘못(4xx)이면") {
            then("응답은 4xx·WARN 이고 Sentry 이벤트를 보내지 않는다") {
                verify(
                    listOf(
                        Case("비즈니스 예외 4xx", BusinessException(ErrorCode.INVALID_REQUEST), 400, Level.WARN, null),
                        Case("스프링 MVC 예외 4xx", HttpRequestMethodNotSupportedException("PATCH"), 405, Level.WARN, null),
                        Case("잘못된 인자", IllegalArgumentException("bad"), 400, Level.WARN, null),
                        Case("읽을 수 없는 본문", HttpMessageNotReadableException("bad", MockHttpInputMessage(ByteArray(0))), 400, Level.WARN, null),
                        Case("인자 형식 불일치", MethodArgumentTypeMismatchException("x", Long::class.java, "id", raise, null), 400, Level.WARN, null),
                    ),
                )
            }
        }

        `when`("서버가 스스로 돌려준 5xx(비즈니스 예외)면") {
            then("태그는 응답 상태와 같고 심각도는 error 다 — 서버는 계속 돌고 있으니 fatal 이 아니다") {
                verify(listOf(Case("비즈니스 예외 5xx", BusinessException(ErrorCode.PLACE_SEARCH_FAILED), 502, Level.ERROR, SentryLevel.ERROR)))
            }
        }

        `when`("예상된 저하(동시 상한 초과처럼 부하 때 반복되는 정상 거절)면") {
            then("응답은 5xx 그대로지만 WARN 으로 남기고 Sentry 이벤트를 보내지 않는다 — 쌓여서 진짜 오류를 묻지 않게") {
                verify(listOf(Case("예상된 저하", BusinessException(ErrorCode.TRANSLATION_FAILED, expected = true), 503, Level.WARN, null)))
            }
        }

        `when`("처리되지 않은 서버 오류면") {
            then("태그는 응답 상태와 같고 심각도는 그대로다") {
                verify(
                    listOf(
                        Case("스프링 MVC 예외 5xx", ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE), 503, Level.ERROR, SentryLevel.FATAL),
                        Case("미처리 예외", IllegalStateException("boom"), 500, Level.ERROR, SentryLevel.FATAL),
                    ),
                )
            }
        }
    }
})
