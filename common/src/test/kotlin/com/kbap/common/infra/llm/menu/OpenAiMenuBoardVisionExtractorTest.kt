package com.kbap.common.infra.llm.menu

import com.kbap.common.domain.metering.LlmCallCostIncurred
import com.kbap.common.port.llm.MenuBoardVisionQuotaExhaustedException
import com.kbap.common.port.llm.MenuBoardVisionRateLimitedException
import com.kbap.common.port.llm.MenuBoardVisionUnavailableException
import com.kbap.common.port.llm.OcrItem
import com.kbap.common.infra.llm.model.LlmPricing
import com.openai.core.http.Headers
import com.openai.errors.BadRequestException
import com.openai.errors.InternalServerException
import com.openai.errors.OpenAIIoException
import com.openai.errors.RateLimitException
import com.openai.models.ErrorObject
import java.time.Duration
import java.time.Instant
import java.util.Optional
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.metadata.Usage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal

class OpenAiMenuBoardVisionExtractorTest : BehaviorSpec({
    val pricing = LlmPricing(
        inputUsdPerMillionTokens = 0.15,
        outputUsdPerMillionTokens = 0.60,
        usdToKrw = 1500.0,
    )

    fun responseOf(text: String, model: String, usage: Usage?): ChatResponse {
        val metadata = ChatResponseMetadata.builder().model(model)
        if (usage != null) metadata.usage(usage)
        return ChatResponse(listOf(Generation(AssistantMessage(text))), metadata.build())
    }

    fun chatModelReturning(response: ChatResponse): ChatModel =
        object : ChatModel {
            override fun call(prompt: Prompt): ChatResponse = response
        }

    fun headersOf(vararg pairs: Pair<String, String>): Headers =
        Headers.builder().apply { pairs.forEach { (name, value) -> put(name, value) } }.build()

    fun errorOf(code: String): ErrorObject =
        ErrorObject.builder().code(code).message("error").param(Optional.empty()).type("server").build()

    fun rateLimit(headers: Headers = headersOf(), code: String = "rate_limit_exceeded"): RateLimitException =
        RateLimitException.builder().headers(headers).error(errorOf(code)).build()

    fun chatModelThrowing(): ChatModel =
        object : ChatModel {
            override fun call(prompt: Prompt): ChatResponse = throw RuntimeException("vision 호출 실패")
        }

    fun extractorRecording(
        chatModel: ChatModel,
        configuredModelName: String = "gpt-4o-mini",
    ): Pair<OpenAiMenuBoardVisionExtractor, MutableList<LlmCallCostIncurred>> {
        val recorded = mutableListOf<LlmCallCostIncurred>()
        val publisher = ApplicationEventPublisher { event ->
            if (event is LlmCallCostIncurred) recorded.add(event)
        }
        val extractor = OpenAiMenuBoardVisionExtractor(
            chatModel = chatModel,
            parser = MenuBoardResultParser(),
            imageBaseUrl = "https://cdn.test",
            pricing = pricing,
            configuredModelName = configuredModelName,
            eventPublisher = publisher,
        )
        return extractor to recorded
    }

    val ocrItems = listOf(OcrItem(idx = 0, rawMenuName = "김치찌개"))

    given("vision 응답 수신 시 비용 이벤트 발행") {
        `when`("usage 를 포함한 성공 응답을 받으면") {
            then("토큰 수·HALF_UP 반올림 비용·응답 모델명을 담은 이벤트가 1회 발행된다") {
                val response = responseOf(
                    text = """{"results":[]}""",
                    model = "gpt-4o-mini-2024-07-18",
                    usage = DefaultUsage(1237, 567, 1804),
                )
                val (extractor, recorded) = extractorRecording(chatModelReturning(response))

                extractor.extract("scan/1/menu.jpg", ocrItems)

                recorded shouldHaveSize 1
                val event = recorded.first()
                event.modelName shouldBe "gpt-4o-mini-2024-07-18"
                event.inputTokens shouldBe 1237L
                event.outputTokens shouldBe 567L
                event.costUsd shouldBe BigDecimal("0.000526")
                event.costKrw shouldBe BigDecimal("0.79")
            }
        }

        `when`("응답 메타데이터의 모델명이 비어 있으면") {
            then("구성된 모델명으로 폴백해 발행한다") {
                val response = responseOf(
                    text = """{"results":[]}""",
                    model = "",
                    usage = DefaultUsage(100, 50, 150),
                )
                val (extractor, recorded) = extractorRecording(
                    chatModelReturning(response),
                    configuredModelName = "gpt-4o-mini",
                )

                extractor.extract("scan/1/menu.jpg", ocrItems)

                recorded shouldHaveSize 1
                recorded.first().modelName shouldBe "gpt-4o-mini"
            }
        }

        `when`("응답에 usage 정보가 없으면") {
            then("토큰 수 0 으로 발행한다") {
                val response = responseOf(
                    text = """{"results":[]}""",
                    model = "gpt-4o-mini-2024-07-18",
                    usage = null,
                )
                val (extractor, recorded) = extractorRecording(chatModelReturning(response))

                extractor.extract("scan/1/menu.jpg", ocrItems)

                recorded shouldHaveSize 1
                recorded.first().inputTokens shouldBe 0L
                recorded.first().outputTokens shouldBe 0L
            }
        }

        `when`("응답은 받았으나 파싱이 실패하면") {
            then("파싱 예외가 나도 이벤트는 이미 발행돼 있다") {
                val response = responseOf(
                    text = "메뉴를 찾을 수 없습니다",
                    model = "gpt-4o-mini-2024-07-18",
                    usage = DefaultUsage(1237, 567, 1804),
                )
                val (extractor, recorded) = extractorRecording(chatModelReturning(response))

                shouldThrow<MenuBoardParseException> { extractor.extract("scan/1/menu.jpg", ocrItems) }

                recorded shouldHaveSize 1
            }
        }

        `when`("vision 호출 자체가 응답 없이 실패하면") {
            then("과금이 없으므로 이벤트를 발행하지 않는다") {
                val (extractor, recorded) = extractorRecording(chatModelThrowing())

                shouldThrow<RuntimeException> { extractor.extract("scan/1/menu.jpg", ocrItems) }

                recorded.shouldBeEmpty()
            }
        }

        `when`("이벤트 발행이 예외를 던지면") {
            then("발행 실패는 격리되고 extract 는 정상 결과를 반환한다") {
                val response = responseOf(
                    text = """{"results":[{"name":"김치찌개","koreanName":"김치찌개","price":9000,"matchedIdx":0}]}""",
                    model = "gpt-4o-mini-2024-07-18",
                    usage = DefaultUsage(1000, 500, 1500),
                )
                val throwingPublisher = ApplicationEventPublisher { throw RuntimeException("발행 실패") }
                val extractor = OpenAiMenuBoardVisionExtractor(
                    chatModel = chatModelReturning(response),
                    parser = MenuBoardResultParser(),
                    imageBaseUrl = "https://cdn.test",
                    pricing = pricing,
                    configuredModelName = "gpt-4o-mini",
                    eventPublisher = throwingPublisher,
                )

                val result = extractor.extract("scan/1/menu.jpg", ocrItems)

                result shouldHaveSize 1
                result.first().koreanName shouldBe "김치찌개"
            }
        }
    }

    given("서버 OCR 프롬프트 분기 — 스캔 v2") {
        fun promptCapturing(response: ChatResponse): Pair<ChatModel, () -> Prompt> {
            var captured: Prompt? = null
            val chatModel = object : ChatModel {
                override fun call(prompt: Prompt): ChatResponse {
                    captured = prompt
                    return response
                }
            }
            return chatModel to { captured!! }
        }

        val response = responseOf(
            text = """{"results":[{"name":"김치찌개","koreanName":"김치찌개","price":9000}]}""",
            model = "gpt-4o-mini-2024-07-18",
            usage = DefaultUsage(100, 50, 150),
        )

        `when`("ocrItems 가 비어 있으면") {
            then("클라이언트 힌트·matchedIdx 지시가 없는 프롬프트로 호출하고 추출·정제 규칙은 유지한다") {
                val (chatModel, capturedPrompt) = promptCapturing(response)
                val (extractor, _) = extractorRecording(chatModel)

                val result = extractor.extract("scan/1/menu.jpg", emptyList())

                result shouldHaveSize 1
                result.first().matchedIdx shouldBe null

                val prompt = capturedPrompt()
                val systemText = prompt.instructions.first { it is SystemMessage }.text
                systemText shouldNotContain "matchedIdx"
                systemText shouldNotContain "OCR"
                systemText shouldContain "메뉴명은 한국어 음식명만 남겨라"
                systemText shouldContain "가격을 찾지 못했거나 확실하지 않은 메뉴는 price를 0으로 하라"
                systemText shouldContain """[{"name": "메뉴명", "price": 8000}]"""
                val userText = prompt.instructions.first { it is UserMessage }.text
                userText shouldNotContain "OCR"
            }
        }

        `when`("ocrItems 가 있으면") {
            then("기존 힌트 프롬프트(OCR 목록·matchedIdx 지시)를 유지한다") {
                val (chatModel, capturedPrompt) = promptCapturing(response)
                val (extractor, _) = extractorRecording(chatModel)

                extractor.extract("scan/1/menu.jpg", listOf(OcrItem(idx = 3, rawMenuName = "김치피개")))

                val prompt = capturedPrompt()
                prompt.instructions.first { it is SystemMessage }.text shouldContain "matchedIdx"
                val userText = prompt.instructions.first { it is UserMessage }.text
                userText shouldContain "OCR:"
                userText shouldContain "3: 김치피개"
            }
        }
    }

    given("교체된 vision 모델 단가") {
        `when`("입력·출력 각 100만 토큰을 쓴 응답을 받으면") {
            then("입력 0.2 / 출력 1.2 단가로 비용이 산정된다") {
                val lunaPricing = LlmPricing(
                    inputUsdPerMillionTokens = 0.2,
                    outputUsdPerMillionTokens = 1.2,
                    usdToKrw = 1500.0,
                )
                val recorded = mutableListOf<LlmCallCostIncurred>()
                val response = responseOf(
                    text = """{"results":[]}""",
                    model = "gpt-5.6-luna",
                    usage = DefaultUsage(1_000_000, 1_000_000, 2_000_000),
                )
                val extractor = OpenAiMenuBoardVisionExtractor(
                    chatModel = chatModelReturning(response),
                    parser = MenuBoardResultParser(),
                    imageBaseUrl = "https://cdn.test",
                    pricing = lunaPricing,
                    configuredModelName = "gpt-5.6-luna",
                    eventPublisher = { event -> if (event is LlmCallCostIncurred) recorded.add(event) },
                )

                extractor.extract("scan/1/menu.jpg", listOf(OcrItem(idx = 0, rawMenuName = "김치찌개")))

                recorded shouldHaveSize 1
                recorded.first().costUsd shouldBe BigDecimal("1.400000")
                recorded.first().costKrw shouldBe BigDecimal("2100.00")
            }
        }
    }

    given("벤더 429 응답") {
        fun chatModelFailingWith(e: Throwable): ChatModel =
            object : ChatModel {
                override fun call(prompt: Prompt): ChatResponse = throw e
            }

        `when`("x-should-retry 가 false 인 429(요청이 TPM 을 넘음)를 받으면") {
            then("재시도 없이 즉시 rate-limit 예외로 바꾸고 한도 헤더를 메시지에 남긴다") {
                val e = rateLimit(headersOf("x-should-retry" to "false", "x-ratelimit-remaining-requests" to "0", "x-ratelimit-remaining-tokens" to "1200"))
                val (extractor, recorded) = extractorRecording(chatModelFailingWith(e))

                val thrown = shouldThrow<MenuBoardVisionRateLimitedException> { extractor.extract("scan/1/menu.jpg", ocrItems) }

                thrown.exhausted shouldBe false
                thrown.retryAfterSeconds shouldBe null
                thrown.message shouldContain "remaining-requests=0"
                thrown.message shouldContain "remaining-tokens=1200"
                recorded.shouldBeEmpty()
            }
        }

        `when`("Retry-After 가 실린 즉시 거절 429 를 받으면") {
            then("재시도 권고 초를 예외에 담는다") {
                val e = rateLimit(headersOf("x-should-retry" to "false", "retry-after" to "20"))
                val (extractor, _) = extractorRecording(chatModelFailingWith(e))

                shouldThrow<MenuBoardVisionRateLimitedException> { extractor.extract("scan/1/menu.jpg", ocrItems) }
                    .retryAfterSeconds shouldBe 20L
            }
        }

        `when`("잔액·한도 계열 429(insufficient_quota)를 받으면") {
            then("재시도 없이 quota 소진 예외로 바꾼다") {
                val (extractor, _) = extractorRecording(chatModelFailingWith(rateLimit(code = "insufficient_quota")))

                shouldThrow<MenuBoardVisionQuotaExhaustedException> { extractor.extract("scan/1/menu.jpg", ocrItems) }
                    .code shouldBe "insufficient_quota"
            }
        }
    }

    given("일시적 벤더 오류 재시도 — 대기 시간 예산 3초") {
        val success = responseOf(
            text = """{"results":[{"name":"김치찌개","koreanName":"김치찌개","price":9000,"matchedIdx":0}]}""",
            model = "gpt-4o-mini-2024-07-18",
            usage = DefaultUsage(100, 50, 150),
        )

        fun serverError(): InternalServerException =
            InternalServerException.builder().statusCode(500).headers(headersOf()).error(errorOf("server_error")).build()

        fun badRequest(): BadRequestException =
            BadRequestException.builder().headers(headersOf()).error(errorOf("invalid_request_error")).build()

        fun chatModelSequence(vararg outcomes: () -> ChatResponse): ChatModel {
            val queue = ArrayDeque(outcomes.toList())
            return object : ChatModel {
                override fun call(prompt: Prompt): ChatResponse = (if (queue.size > 1) queue.removeFirst() else queue.first())()
            }
        }

        class FakeTime {
            var now: Instant = Instant.EPOCH
            val sleeps = mutableListOf<Duration>()
            fun advance(by: Duration) { now = now.plus(by) }
        }

        fun extractorWithBudget(chatModel: ChatModel, time: FakeTime): OpenAiMenuBoardVisionExtractor =
            OpenAiMenuBoardVisionExtractor(
                chatModel = chatModel,
                parser = MenuBoardResultParser(),
                imageBaseUrl = "https://cdn.test",
                pricing = pricing,
                configuredModelName = "gpt-4o-mini",
                retryBudget = Duration.ofSeconds(3),
                sleep = { time.sleeps += it; time.advance(it) },
                now = { time.now },
            )

        `when`("첫 시도가 Retry-After 1초짜리 429 이고 두 번째가 성공하면") {
            then("1초 대기 후 재시도해 결과를 돌려준다") {
                val time = FakeTime()
                val extractor = extractorWithBudget(
                    chatModelSequence({ throw rateLimit(headersOf("retry-after" to "1")) }, { success }),
                    time,
                )

                val result = extractor.extract("scan/1/menu.jpg", ocrItems)

                result shouldHaveSize 1
                time.sleeps shouldBe listOf(Duration.ofSeconds(1))
            }
        }

        `when`("Retry-After 2초짜리 429 가 계속되면") {
            then("대기 합이 예산을 넘기 전에 멈추고 재시도 소진 rate-limit 예외를 던진다") {
                val time = FakeTime()
                val extractor = extractorWithBudget(
                    chatModelSequence({ throw rateLimit(headersOf("retry-after" to "2", "x-ratelimit-remaining-requests" to "0")) }),
                    time,
                )

                val thrown = shouldThrow<MenuBoardVisionRateLimitedException> { extractor.extract("scan/1/menu.jpg", ocrItems) }

                thrown.exhausted shouldBe true
                thrown.retryAfterSeconds shouldBe 2L
                thrown.message shouldContain "remaining-requests=0"
                time.sleeps shouldBe listOf(Duration.ofSeconds(2))
            }
        }

        `when`("Retry-After 없는 429 가 계속되면") {
            then("0.5초부터 두 배씩 지터를 섞어 기다리고 대기 합은 예산 이하다") {
                val time = FakeTime()
                val extractor = extractorWithBudget(chatModelSequence({ throw rateLimit() }), time)

                shouldThrow<MenuBoardVisionRateLimitedException> { extractor.extract("scan/1/menu.jpg", ocrItems) }
                    .exhausted shouldBe true

                time.sleeps.size shouldBeGreaterThanOrEqual 2
                time.sleeps[0].toMillis() shouldBeInRange 375L..625L
                time.sleeps[1].toMillis() shouldBeInRange 750L..1250L
                time.sleeps.sumOf { it.toMillis() } shouldBeLessThanOrEqual 3000L
            }
        }

        `when`("5xx 뒤에 성공하면") {
            then("재시도해 결과를 돌려준다") {
                val time = FakeTime()
                val extractor = extractorWithBudget(chatModelSequence({ throw serverError() }, { success }), time)

                extractor.extract("scan/1/menu.jpg", ocrItems) shouldHaveSize 1
                time.sleeps shouldHaveSize 1
            }
        }

        `when`("네트워크 오류가 계속되면") {
            then("예산 소진 후 서버 장애 예외를 던진다") {
                val time = FakeTime()
                val extractor = extractorWithBudget(chatModelSequence({ throw OpenAIIoException("timeout") }), time)

                shouldThrow<MenuBoardVisionUnavailableException> { extractor.extract("scan/1/menu.jpg", ocrItems) }
                time.sleeps.sumOf { it.toMillis() } shouldBeLessThanOrEqual 3000L
            }
        }

        `when`("400 을 받으면") {
            then("재시도 없이 원 예외를 그대로 던진다") {
                val time = FakeTime()
                val extractor = extractorWithBudget(chatModelSequence({ throw badRequest() }), time)

                shouldThrow<BadRequestException> { extractor.extract("scan/1/menu.jpg", ocrItems) }
                time.sleeps.shouldBeEmpty()
            }
        }

        `when`("Retry-After 가 0 인 429 가 계속되면") {
            then("0 을 대기 시간으로 쓰지 않고 백오프로 기다리며 예산 안에서 멈춘다") {
                val time = FakeTime()
                val extractor = extractorWithBudget(chatModelSequence({ throw rateLimit(headersOf("retry-after" to "0")) }), time)

                shouldThrow<MenuBoardVisionRateLimitedException> { extractor.extract("scan/1/menu.jpg", ocrItems) }
                    .exhausted shouldBe true

                time.sleeps[0].toMillis() shouldBeInRange 375L..625L
                time.sleeps.sumOf { it.toMillis() } shouldBeLessThanOrEqual 3000L
            }
        }

        `when`("실패한 호출 자체가 오래 걸려 예산을 넘기면") {
            then("대기 없이 즉시 재시도 소진으로 끝난다") {
                val time = FakeTime()
                val slowFailure = object : ChatModel {
                    override fun call(prompt: Prompt): ChatResponse {
                        time.advance(Duration.ofSeconds(4))
                        throw rateLimit(headersOf("retry-after" to "1"))
                    }
                }
                val extractor = extractorWithBudget(slowFailure, time)

                shouldThrow<MenuBoardVisionRateLimitedException> { extractor.extract("scan/1/menu.jpg", ocrItems) }
                    .exhausted shouldBe true

                time.sleeps.shouldBeEmpty()
            }
        }
    }
})
