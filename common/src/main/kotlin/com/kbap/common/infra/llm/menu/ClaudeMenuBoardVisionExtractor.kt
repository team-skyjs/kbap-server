package com.kbap.common.infra.llm.menu

import com.anthropic.core.http.Headers
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.kbap.common.infra.llm.claude.ClaudeChat
import com.kbap.common.infra.llm.menu.OpenAiMenuBoardVisionExtractor.Companion.BATCH_LINE_SYSTEM_PROMPT
import com.kbap.common.infra.llm.menu.OpenAiMenuBoardVisionExtractor.Companion.BATCH_LINE_USER_PROMPT
import com.kbap.common.infra.llm.menu.OpenAiMenuBoardVisionExtractor.Companion.PROCESSED_FOOD_PHOTO_SYSTEM_PROMPT
import com.kbap.common.infra.llm.menu.OpenAiMenuBoardVisionExtractor.Companion.PROCESSED_FOOD_PHOTO_USER_PROMPT
import com.kbap.common.infra.llm.menu.OpenAiMenuBoardVisionExtractor.Companion.SERVER_OCR_SYSTEM_PROMPT
import com.kbap.common.infra.llm.menu.OpenAiMenuBoardVisionExtractor.Companion.SYSTEM_PROMPT
import com.kbap.common.infra.llm.menu.OpenAiMenuBoardVisionExtractor.Companion.userPromptWith
import com.kbap.common.port.llm.ExtractedMenu
import com.kbap.common.port.llm.MenuBoardLine
import com.kbap.common.port.llm.MenuBoardVisionQuotaExhaustedException
import com.kbap.common.port.llm.MenuBoardVisionRateLimitedException
import com.kbap.common.port.llm.MenuBoardVisionUnavailableException
import com.kbap.common.port.llm.OcrItem
import com.kbap.common.port.llm.ProcessedFoodPhotoReading
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * 메뉴판·가공식품 사진 비전(Claude). 프롬프트·파서는 OpenAI 구현과 공유하고, 실패 의미(한도 소진·속도 제한·일시 불가)와 재시도 예산을 그대로 옮긴다.
 * - 크레딧·지출 한도 소진(billing_error, "credit balance"·"usage limit" 400) → QuotaExhausted (재시도 없음)
 * - 429 → retry-after 를 따라 예산 안에서 재시도, 넘기면 RateLimited(exhausted=true). x-should-retry:false 면 즉시 RateLimited(exhausted=false)
 * - 5xx(529 overloaded 포함)·네트워크 → 예산 안에서 재시도, 넘기면 Unavailable
 */
class ClaudeMenuBoardVisionExtractor(
    private val chat: ClaudeChat,
    private val parser: MenuBoardResultParser,
    private val imageBaseUrl: String,
    private val retryBudget: Duration = Duration.ofSeconds(10),
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
    private val now: () -> Instant = Instant::now,
) : MenuBoardVisionClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun extract(imagePath: String, ocrItems: List<OcrItem>): List<ExtractedMenu> =
        parser.parse(ask(imagePath, if (ocrItems.isEmpty()) SERVER_OCR_SYSTEM_PROMPT else SYSTEM_PROMPT, userPromptWith(ocrItems)))

    override fun extractLines(imagePath: String): List<MenuBoardLine> =
        parser.parseLines(ask(imagePath, BATCH_LINE_SYSTEM_PROMPT, BATCH_LINE_USER_PROMPT))

    override fun read(imagePath: String): ProcessedFoodPhotoReading =
        parser.parsePhoto(ask(imagePath, PROCESSED_FOOD_PHOTO_SYSTEM_PROMPT, PROCESSED_FOOD_PHOTO_USER_PROMPT))

    private fun ask(imagePath: String, systemPrompt: String, userText: String): String {
        val imageUrl = "${imageBaseUrl.trimEnd('/')}/${imagePath.trimStart('/')}"
        val reply = callWithRetryBudget { chat.ask(systemPrompt, userText, imageUrl = imageUrl) }
        log.info(
            "vision 토큰 사용량 model={} inputTokens={} outputTokens={} costUsd={} costKrw={} stopReason={}",
            reply.model,
            reply.inputTokens,
            reply.outputTokens,
            reply.cost.costUsd.toPlainString(),
            reply.cost.costKrw.toPlainString(),
            reply.stopReason?.asString(),
        )
        return reply.text
    }

    private fun callWithRetryBudget(call: () -> ClaudeChat.Reply): ClaudeChat.Reply {
        val deadline = now().plus(retryBudget)
        var attempt = 0
        while (true) {
            try {
                return call()
            } catch (e: RateLimitException) {
                quotaCodeOf(e)?.let { throw MenuBoardVisionQuotaExhaustedException(it, e) }
                val retryAfter = retryAfterOf(e.headers())
                val limits = limitsOf(e.headers())
                if (e.headers().values("x-should-retry").firstOrNull() == "false") {
                    throw MenuBoardVisionRateLimitedException(retryAfter?.seconds, exhausted = false, limits, e)
                }
                if (!waitWithinBudget(retryAfter, attempt++, deadline)) {
                    throw MenuBoardVisionRateLimitedException(retryAfter?.seconds, exhausted = true, limits, e)
                }
            } catch (e: AnthropicServiceException) {
                quotaCodeOf(e)?.let { throw MenuBoardVisionQuotaExhaustedException(it, e) }
                if (e.statusCode() < 500) throw e
                if (!waitWithinBudget(retryAfterOf(e.headers()), attempt++, deadline)) throw MenuBoardVisionUnavailableException(e)
            } catch (e: AnthropicIoException) {
                if (!waitWithinBudget(null, attempt++, deadline)) throw MenuBoardVisionUnavailableException(e)
            }
        }
    }

    private fun waitWithinBudget(retryAfter: Duration?, attempt: Int, deadline: Instant): Boolean {
        val wait = retryAfter?.takeIf { it > Duration.ZERO } ?: backoffWithJitter(attempt)
        if (now().plus(wait).isAfter(deadline)) return false
        sleep(wait)
        return true
    }

    private fun backoffWithJitter(attempt: Int): Duration =
        Duration.ofMillis(((500L shl attempt) * Random.nextDouble(0.75, 1.25)).toLong())

    private fun retryAfterOf(headers: Headers): Duration? =
        headers.values("retry-after-ms").firstOrNull()?.toLongOrNull()?.let(Duration::ofMillis)
            ?: headers.values("retry-after").firstOrNull()?.toLongOrNull()?.let(Duration::ofSeconds)

    private fun limitsOf(headers: Headers): String =
        headers.names().filter { it.lowercase().startsWith(RATE_LIMIT_HEADER_PREFIX) }.sorted()
            .mapNotNull { name -> headers.values(name).firstOrNull()?.let { "${name.lowercase().removePrefix(RATE_LIMIT_HEADER_PREFIX)}=$it" } }
            .joinToString(" ")

    companion object {
        private const val RATE_LIMIT_HEADER_PREFIX = "anthropic-ratelimit-"
        private const val BILLING_ERROR = "billing_error"
        private val QUOTA_MESSAGE = Regex("(?i)credit balance|usage limit|spend limit")

        internal fun quotaCodeOf(e: AnthropicServiceException): String? {
            val type = e.errorType().map { it.asString() }.orElse("")
            if (type == BILLING_ERROR) return type
            if (QUOTA_MESSAGE.containsMatchIn(e.message.orEmpty()) || QUOTA_MESSAGE.containsMatchIn(e.body().toString())) return type.ifEmpty { BILLING_ERROR }
            return null
        }
    }
}
