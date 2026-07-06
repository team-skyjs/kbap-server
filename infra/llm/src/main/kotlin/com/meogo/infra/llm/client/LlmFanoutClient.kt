package com.meogo.infra.llm.client

import com.meogo.infra.llm.model.LlmChatRequest
import com.meogo.infra.llm.model.LlmChatResult
import com.meogo.infra.llm.model.LlmFanoutResult
import com.meogo.infra.llm.model.LlmModelFailure
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class LlmFanoutClient(
    private val callers: List<LlmModelCaller>,
    private val executor: Executor,
    private val callTimeout: Duration = Duration.ofSeconds(30),
) {
    fun generate(request: LlmChatRequest): LlmFanoutResult {
        val outcomes = callers
            .map { caller -> outcomeFuture(caller, request) }
            .map { it.join() }

        val successes = outcomes.filterIsInstance<CallOutcome.Success>().map { it.result }
        val failures = outcomes.filterIsInstance<CallOutcome.Failure>().map { it.failure }
        return LlmFanoutResult(successes, failures)
    }

    private fun outcomeFuture(
        caller: LlmModelCaller,
        request: LlmChatRequest,
    ): CompletableFuture<CallOutcome> =
        CompletableFuture
            .supplyAsync({ caller.call(request) }, executor)
            .orTimeout(callTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .handle { content, throwable ->
                if (throwable == null) {
                    CallOutcome.Success(LlmChatResult(caller.modelId, content))
                } else {
                    CallOutcome.Failure(LlmModelFailure(caller.modelId, reasonOf(throwable)))
                }
            }

    private fun reasonOf(throwable: Throwable): String {
        val cause = if (throwable is java.util.concurrent.CompletionException && throwable.cause != null) {
            throwable.cause!!
        } else {
            throwable
        }
        return cause.message ?: cause::class.simpleName ?: "unknown"
    }

    private sealed interface CallOutcome {
        data class Success(val result: LlmChatResult) : CallOutcome

        data class Failure(val failure: LlmModelFailure) : CallOutcome
    }
}
