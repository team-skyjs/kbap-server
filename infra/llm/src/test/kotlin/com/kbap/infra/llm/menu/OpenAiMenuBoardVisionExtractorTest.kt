package com.kbap.infra.llm.menu

import com.kbap.common.core.llm.LlmCallCostIncurred
import com.kbap.common.core.scan.OcrItem
import com.kbap.infra.llm.model.LlmPricing
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.springframework.ai.chat.messages.AssistantMessage
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
})
