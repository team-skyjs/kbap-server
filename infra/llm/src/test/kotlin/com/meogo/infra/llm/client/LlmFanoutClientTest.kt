package com.meogo.infra.llm.client

import com.meogo.infra.llm.model.LlmChatRequest
import com.meogo.infra.llm.model.LlmChatResult
import com.meogo.infra.llm.model.LlmModelId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LlmFanoutClientTest : BehaviorSpec({

    val executor = Executors.newVirtualThreadPerTaskExecutor()

    given("정상 응답하는 caller 3개(OPENAI/UPSTAGE/GEMINI)로 구성된 fan-out 클라이언트") {
        val callers = listOf(
            SuccessfulCaller(LlmModelId.OPENAI, "openai-content"),
            SuccessfulCaller(LlmModelId.UPSTAGE, "upstage-content"),
            SuccessfulCaller(LlmModelId.GEMINI, "gemini-content"),
        )
        val client = LlmFanoutClient(callers, executor)

        `when`("하나의 프롬프트로 generate 를 호출하면") {
            val result = client.generate(LlmChatRequest(prompt = "안녕"))

            then("3개 모델 결과가 모두 successes 에 담기고 failures 는 비어 있다") {
                result.successes shouldHaveSize 3
                result.failures.shouldBeEmpty()
            }

            then("각 결과의 content 가 페이크 반환값과 일치하고 modelId 귀속이 정확하다") {
                result.successes shouldContainExactlyInAnyOrder listOf(
                    LlmChatResult(LlmModelId.OPENAI, "openai-content"),
                    LlmChatResult(LlmModelId.UPSTAGE, "upstage-content"),
                    LlmChatResult(LlmModelId.GEMINI, "gemini-content"),
                )
            }
        }
    }

    given("3개 중 UPSTAGE 가 예외를 던지고 나머지 2개는 정상인 caller 구성") {
        val callers = listOf(
            SuccessfulCaller(LlmModelId.OPENAI, "openai-content"),
            FailingCaller(LlmModelId.UPSTAGE, "upstream timeout"),
            SuccessfulCaller(LlmModelId.GEMINI, "gemini-content"),
        )
        val client = LlmFanoutClient(callers, executor)

        `when`("generate 를 호출하면") {
            val result = client.generate(LlmChatRequest(prompt = "안녕"))

            then("성공 2개를 반환하고 전체 호출은 중단되지 않는다") {
                result.successes.map { it.modelId } shouldContainExactlyInAnyOrder listOf(
                    LlmModelId.OPENAI,
                    LlmModelId.GEMINI,
                )
            }

            then("실패한 UPSTAGE 는 failures 에만 식별 가능한 형태로 분리된다") {
                result.failures shouldHaveSize 1
                result.failures.single().modelId shouldBe LlmModelId.UPSTAGE
            }

            then("한 modelId 는 successes 와 failures 중 한쪽에만 존재한다") {
                val successIds = result.successes.map { it.modelId }.toSet()
                val failureIds = result.failures.map { it.modelId }.toSet()
                successIds.intersect(failureIds).shouldBeEmpty()
                result.attemptedCount() shouldBe 3
            }
        }
    }

    given("모든 caller 가 공유 배리어에 진입해야 통과하는 병렬성 검증 구성") {
        val callerCount = 3
        val entryBarrier = CountDownLatch(callerCount)
        val callers = listOf(
            ConcurrencyProbingCaller(LlmModelId.OPENAI, entryBarrier),
            ConcurrencyProbingCaller(LlmModelId.UPSTAGE, entryBarrier),
            ConcurrencyProbingCaller(LlmModelId.GEMINI, entryBarrier),
        )
        val client = LlmFanoutClient(callers, executor)

        `when`("generate 를 호출하면") {
            val result = client.generate(LlmChatRequest(prompt = "안녕"))

            then("모든 caller 가 동시에 진입해 배리어를 통과한다(순차 실행이면 타임아웃으로 실패)") {
                result.successes shouldHaveSize callerCount
                result.successes.map { it.content }.toSet() shouldBe setOf("concurrent")
            }
        }
    }

    given("모든 caller 가 예외를 던지는 전멸 구성") {
        val callers = listOf(
            FailingCaller(LlmModelId.OPENAI, "openai down"),
            FailingCaller(LlmModelId.UPSTAGE, "upstage down"),
            FailingCaller(LlmModelId.GEMINI, "gemini down"),
        )
        val client = LlmFanoutClient(callers, executor)

        `when`("generate 를 호출하면") {
            val result = client.generate(LlmChatRequest(prompt = "안녕"))

            then("예외를 밖으로 던지지 않고 successes 는 비고 failures 에 전 caller 가 담긴다") {
                result.successes.shouldBeEmpty()
                result.failures shouldHaveSize callers.size
            }

            then("isAllFailed 는 true 이고 attemptedCount 는 caller 수와 같다") {
                result.isAllFailed() shouldBe true
                result.attemptedCount() shouldBe callers.size
            }
        }
    }

    given("예외 caller 가 원인 메시지 'boom' 을 던지는 구성") {
        val callers = listOf(FailingCaller(LlmModelId.OPENAI, "boom"))
        val client = LlmFanoutClient(callers, executor)

        `when`("generate 를 호출하면") {
            val result = client.generate(LlmChatRequest(prompt = "안녕"))

            then("failure message 가 CompletionException 래퍼가 아니라 원인 메시지 'boom' 을 담는다") {
                result.failures.single().message shouldBe "boom"
            }
        }
    }

    given("한 caller 는 호출 타임아웃보다 오래 걸리고 나머지 2개는 즉시 응답하는 구성") {
        val callers = listOf(
            SuccessfulCaller(LlmModelId.OPENAI, "openai-content"),
            HangingCaller(LlmModelId.UPSTAGE, sleepMillis = 2000),
            SuccessfulCaller(LlmModelId.GEMINI, "gemini-content"),
        )
        val client = LlmFanoutClient(callers, executor, callTimeout = Duration.ofMillis(200))

        `when`("generate 를 호출하면") {
            val startedAt = System.nanoTime()
            val result = client.generate(LlmChatRequest(prompt = "안녕"))
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

            then("hang 된 모델을 기다리지 않고 타임아웃 안에 이미 끝난 성공분을 반환한다") {
                elapsedMillis shouldBeLessThan 2000
                result.successes.map { it.modelId } shouldContainExactlyInAnyOrder listOf(
                    LlmModelId.OPENAI,
                    LlmModelId.GEMINI,
                )
            }

            then("타임아웃된 UPSTAGE 는 failures 로 분리된다") {
                result.failures shouldHaveSize 1
                result.failures.single().modelId shouldBe LlmModelId.UPSTAGE
            }
        }
    }

    given("caller 가 하나도 없는 빈 리스트 구성") {
        val client = LlmFanoutClient(emptyList(), executor)

        `when`("generate 를 호출하면") {
            val result = client.generate(LlmChatRequest(prompt = "안녕"))

            then("예외 없이 successes 와 failures 가 모두 빈 리스트다") {
                result shouldNotBe null
                result.successes.shouldBeEmpty()
                result.failures.shouldBeEmpty()
            }
        }
    }

    given("modelId 별로 다른 요청을 전달하는 per-model fan-out 구성") {
        val openai = CapturingCaller(LlmModelId.OPENAI)
        val upstage = CapturingCaller(LlmModelId.UPSTAGE)
        val gemini = CapturingCaller(LlmModelId.GEMINI)
        val client = LlmFanoutClient(listOf(openai, upstage, gemini), executor)

        `when`("generate(requestFor) 를 modelId 기반 요청 함수로 호출하면") {
            val result = client.generate { modelId -> LlmChatRequest(prompt = "prompt-${modelId.name}") }

            then("각 caller 는 자신의 modelId 에 대응하는 요청을 정확히 1회 받는다") {
                openai.callCount() shouldBe 1
                upstage.callCount() shouldBe 1
                gemini.callCount() shouldBe 1
                openai.lastPrompt() shouldBe "prompt-OPENAI"
                upstage.lastPrompt() shouldBe "prompt-UPSTAGE"
                gemini.lastPrompt() shouldBe "prompt-GEMINI"
            }

            then("3개 모델 결과가 모두 successes 에 담기고 failures 는 비어 있다") {
                result.successes.map { it.modelId } shouldContainExactlyInAnyOrder listOf(
                    LlmModelId.OPENAI,
                    LlmModelId.UPSTAGE,
                    LlmModelId.GEMINI,
                )
                result.failures.shouldBeEmpty()
            }
        }
    }

    given("per-model 요청에서 UPSTAGE 만 실패하는 구성") {
        val callers = listOf(
            CapturingCaller(LlmModelId.OPENAI),
            FailingCaller(LlmModelId.UPSTAGE, "upstage down"),
            CapturingCaller(LlmModelId.GEMINI),
        )
        val client = LlmFanoutClient(callers, executor)

        `when`("generate(requestFor) 를 호출하면") {
            val result = client.generate { modelId -> LlmChatRequest(prompt = "p-${modelId.name}") }

            then("부분 실패 수집(successes/failures) 의미가 generate(request) 와 동일하다") {
                result.successes.map { it.modelId } shouldContainExactlyInAnyOrder listOf(
                    LlmModelId.OPENAI,
                    LlmModelId.GEMINI,
                )
                result.failures.single().modelId shouldBe LlmModelId.UPSTAGE
                result.attemptedCount() shouldBe 3
            }
        }
    }
})

private class CapturingCaller(
    override val modelId: LlmModelId,
) : LlmModelCaller {
    private val count = AtomicInteger()

    @Volatile
    private var lastRequest: LlmChatRequest? = null

    override fun call(request: LlmChatRequest): String {
        count.incrementAndGet()
        lastRequest = request
        return "content-${modelId.name}"
    }

    fun callCount(): Int = count.get()

    fun lastPrompt(): String? = lastRequest?.prompt
}

private class SuccessfulCaller(
    override val modelId: LlmModelId,
    private val content: String,
) : LlmModelCaller {
    override fun call(request: LlmChatRequest): String = content
}

private class FailingCaller(
    override val modelId: LlmModelId,
    private val failureMessage: String,
) : LlmModelCaller {
    override fun call(request: LlmChatRequest): String = throw RuntimeException(failureMessage)
}

private class HangingCaller(
    override val modelId: LlmModelId,
    private val sleepMillis: Long,
) : LlmModelCaller {
    override fun call(request: LlmChatRequest): String {
        Thread.sleep(sleepMillis)
        return "should-have-timed-out"
    }
}

private class ConcurrencyProbingCaller(
    override val modelId: LlmModelId,
    private val entryBarrier: CountDownLatch,
) : LlmModelCaller {
    override fun call(request: LlmChatRequest): String {
        entryBarrier.countDown()
        val allEntered = entryBarrier.await(2, TimeUnit.SECONDS)
        return if (allEntered) "concurrent" else "sequential"
    }
}
