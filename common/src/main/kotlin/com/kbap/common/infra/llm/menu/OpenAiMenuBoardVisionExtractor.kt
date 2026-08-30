package com.kbap.common.infra.llm.menu

import com.kbap.common.domain.metering.LlmCallCostIncurred
import com.kbap.common.port.llm.ExtractedMenu
import com.kbap.common.port.llm.MenuBoardVisionExtractor
import com.kbap.common.port.llm.MenuBoardVisionQuotaExhaustedException
import com.kbap.common.port.llm.MenuBoardVisionRateLimitedException
import com.kbap.common.port.llm.MenuBoardVisionUnavailableException
import com.kbap.common.port.llm.OcrItem
import com.kbap.common.infra.llm.model.LlmPricing
import com.openai.core.http.Headers
import com.openai.errors.InternalServerException
import com.openai.errors.OpenAIIoException
import com.openai.errors.RateLimitException
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.content.Media
import org.springframework.context.ApplicationEventPublisher
import org.springframework.util.MimeType
import org.springframework.util.MimeTypeUtils
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.time.Duration

class OpenAiMenuBoardVisionExtractor(
    private val chatModel: ChatModel,
    private val parser: MenuBoardResultParser,
    private val imageBaseUrl: String,
    private val pricing: LlmPricing = LlmPricing.UNPRICED,
    private val configuredModelName: String = "",
    private val eventPublisher: ApplicationEventPublisher = ApplicationEventPublisher { },
    private val retryBudget: Duration = Duration.ofSeconds(10),
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) : MenuBoardVisionExtractor {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun extract(imagePath: String, ocrItems: List<OcrItem>): List<ExtractedMenu> {
        val imageUrl = URI.create("${imageBaseUrl.trimEnd('/')}/${imagePath.trimStart('/')}")
        val media = Media.builder().mimeType(mimeTypeOf(imagePath)).data(imageUrl).build()
        val userMessage = UserMessage.builder().text(userPromptWith(ocrItems)).media(media).build()

        val systemPrompt = if (ocrItems.isEmpty()) SERVER_OCR_SYSTEM_PROMPT else SYSTEM_PROMPT
        val response = callWithRetryBudget(Prompt(listOf(SystemMessage(systemPrompt), userMessage)))
        val cost = costIncurredFrom(response)
        publishCost(cost)
        logTokenUsage(cost, response.metadata.usage.totalTokens)
        val raw = response.results.firstOrNull()?.output?.text.orEmpty()
        return parser.parse(raw)
    }

    private fun callWithRetryBudget(prompt: Prompt): ChatResponse {
        try {
            return chatModel.call(prompt)
        } catch (e: RateLimitException) {
            val code = e.code().orElse("")
            if (code in QUOTA_CODES) throw MenuBoardVisionQuotaExhaustedException(code, e)
            val retryAfter = retryAfterOf(e.headers())
            val limits = limitsOf(e.headers())
            if (e.headers().values("x-should-retry").firstOrNull() == "false") {
                throw MenuBoardVisionRateLimitedException(retryAfter?.seconds, exhausted = false, limits, e)
            }
            throw MenuBoardVisionRateLimitedException(retryAfter?.seconds, exhausted = true, limits, e)
        } catch (e: InternalServerException) {
            throw MenuBoardVisionUnavailableException(e)
        } catch (e: OpenAIIoException) {
            throw MenuBoardVisionUnavailableException(e)
        }
    }

    private fun retryAfterOf(headers: Headers): Duration? =
        headers.values("retry-after-ms").firstOrNull()?.toLongOrNull()?.let(Duration::ofMillis)
            ?: headers.values("retry-after").firstOrNull()?.toLongOrNull()?.let(Duration::ofSeconds)

    private fun limitsOf(headers: Headers): String =
        LIMIT_HEADER_KEYS
            .mapNotNull { key -> headers.values("x-ratelimit-$key").firstOrNull()?.let { "$key=$it" } }
            .joinToString(" ")

    private fun costIncurredFrom(response: ChatResponse): LlmCallCostIncurred {
        val usage = response.metadata.usage
        val promptTokens = (usage.promptTokens ?: 0).toLong()
        val completionTokens = (usage.completionTokens ?: 0).toLong()
        if (promptTokens == 0L && completionTokens == 0L) {
            log.warn("vision 응답에 usage 정보가 없어 토큰 수를 0 으로 기록합니다")
        }
        if (pricing == LlmPricing.UNPRICED) {
            log.warn("vision 단가가 설정되지 않아 비용을 0 으로 기록합니다")
        }
        val modelName = response.metadata.model?.takeIf { it.isNotBlank() } ?: configuredModelName
        return LlmCallCostIncurred(
            modelName = modelName,
            inputTokens = promptTokens,
            outputTokens = completionTokens,
            costUsd = BigDecimal.valueOf(pricing.costUsd(promptTokens, completionTokens)).setScale(6, RoundingMode.HALF_UP),
            costKrw = BigDecimal.valueOf(pricing.costKrw(promptTokens, completionTokens)).setScale(2, RoundingMode.HALF_UP),
        )
    }

    private fun publishCost(cost: LlmCallCostIncurred) {
        try {
            eventPublisher.publishEvent(cost)
        } catch (e: Exception) {
            log.warn("LLM 호출 비용 이벤트 발행 실패 modelName={}", cost.modelName, e)
        }
    }

    private fun logTokenUsage(cost: LlmCallCostIncurred, totalTokens: Int?) {
        log.info(
            "vision 토큰 사용량 promptTokens={} completionTokens={} totalTokens={} costUsd={} costKrw={}",
            cost.inputTokens,
            cost.outputTokens,
            totalTokens,
            cost.costUsd.toPlainString(),
            cost.costKrw.toPlainString(),
        )
    }

    private fun userPromptWith(ocrItems: List<OcrItem>): String {
        if (ocrItems.isEmpty()) return "이 메뉴판 사진에서 메뉴명과 가격을 추출하라."
        val ocrLines = ocrItems.joinToString("\n") { "${it.idx}: ${it.rawMenuName}" }
        val header = """
            이 메뉴판 사진에서 메뉴명과 가격을 추출해줘.
            아래는 클라이언트가 같은 사진을 OCR 한 결과다(형식 "idx: 텍스트"). 오탈자가 섞여 있을 수 있으니 사진을 기준으로 판단하고, 각 메뉴에 대응하는 idx 를 matchedIdx 에 넣어라.
            OCR:
        """.trimIndent()
        return "$header\n$ocrLines"
    }

    private fun mimeTypeOf(path: String): MimeType =
        when (path.substringAfterLast('.', "").lowercase()) {
            "png" -> MimeTypeUtils.IMAGE_PNG
            "webp" -> MimeType.valueOf("image/webp")
            "gif" -> MimeTypeUtils.IMAGE_GIF
            else -> MimeTypeUtils.IMAGE_JPEG
        }

    companion object {
        private val QUOTA_CODES = setOf(
            "insufficient_quota",
            "credit_balance_exhausted",
            "organization_spend_limit_exceeded",
            "project_spend_limit_exceeded",
            "organization_usage_limit_exceeded",
        )

        private val LIMIT_HEADER_KEYS = listOf(
            "limit-requests",
            "limit-tokens",
            "remaining-requests",
            "remaining-tokens",
            "reset-requests",
            "reset-tokens",
            "remaining-project-tokens",
            "reset-project-tokens",
        )

        private val SYSTEM_PROMPT = """
            너는 한국 식당 메뉴판 사진에서 메뉴와 가격을 추출하고, 클라이언트 OCR 항목에 매칭하는 도구다. 반드시 JSON 객체 하나로만 응답한다 — 설명·마크다운·코드펜스 없이.
            형식: {"results":[{"name":"...","koreanName":"...","price":16000,"matchedIdx":0}]}

            [진실의 출처]
            사진과 아래 OCR 목록은 같은 메뉴판에서 나왔다. OCR 은 저해상도·기울기·장식체 탓에 글자가 틀리게 읽힌 결과이므로, 사진 픽셀로 판독되는 글자가 최종 근거다.
            - OCR 이 사진과 다르게 읽혔고 사진에서 글자가 분명히 판독되면, 사진을 따라 고친다(OCR 오탈자 교정. 예: "되지불고기"→"돼지불고기", "삼겹사"→"삼겹살", "5징어볶음"→"오징어볶음").
            - OCR 이 사진과 일치하면 그대로 둔다. 표기가 낯설거나 흔한 메뉴명과 비슷해도, 정상적으로 쓰인 한국어 메뉴명은 흔치 않아도 실재 메뉴이므로 네가 아는 더 흔한 이름으로 바꾸지 마라. 사진에서 확실히 판독되지 않을 때도 OCR 을 보존한다(예: "물냉면"→"밀냉면", "짜글이"→"찌개"로 바꾸지 않는다 — 예시는 원칙의 예시일 뿐 목록이 아니다).
            - 사진에 근거가 없는 OCR 항목(환각)은 결과에서 제외한다.

            [규칙]
            - name: 사진에 표기된 그대로의 메뉴명(외국어 병기 포함).
            - koreanName: 표준 한국어 메뉴명. 사진 표기가 외국어뿐이거나 병기여도 순수 한국어로만 적는다(영문 알파벳 금지). 이 값은 서버 DB 조회 키다.
            - price: 원(KRW) 단위 정수 숫자만, 따옴표 없이(문자열이면 스캔 전체가 실패한다). 통화기호·콤마·"원" 제거(예: "54,000원"→54000). 축약은 환산한다("1.6"/"1.6만"→16000, 천원 축약 "9.0"→9000). 미표기는 null.
            - 메뉴가 아닌 텍스트(상호·전화번호·원산지·영업안내 등)는 제외한다.
            - 한 메뉴에 사이즈별 가격이 여럿이면 항목을 분리한다(예: "김치찌개(소)", "김치찌개(대)").

            [matchedIdx — 커버리지]
            - 각 메뉴를 OCR 목록의 idx 에 매칭한다. 사진 위치가 아니라 텍스트 내용으로 매칭한다(OCR 순서는 사진 배치와 다를 수 있다).
            - 사진에서 하나의 메뉴로 확인되는 항목은 반드시 하나의 result 로 커버한다. 단 한 메뉴가 여러 OCR 조각(예: "삼겹"+"살")으로 쪼개졌으면 조각들을 합쳐 하나의 result 로 만들고 matchedIdx 는 그중 한 조각의 idx 하나만 준다 — 남은 조각 idx 로 별도 result 를 만들지 않는다. 대응 OCR 이 없으면 matchedIdx 는 null.
            - 한 idx 는 최대 하나의 result 에만 쓴다(중복 금지). 한 OCR 항목에 여러 메뉴가 병합돼 있거나 사이즈로 나뉘면, 그중 하나에만 그 idx 를 주고 나머지는 null.
        """.trimIndent()

        private val SERVER_OCR_SYSTEM_PROMPT = """
            너는 메뉴판 분석 전문가다.
            주어진 메뉴판 사진에서 모든 메뉴명과 가격을 직접 읽어 추출하라.
            사진이 메뉴판(음식 이름과 가격이 나열된 판·차림표)으로 추정되지 않거나 메뉴를 하나도 확인할 수 없으면 빈 배열 []만 출력하라.
            사진에서 확실히 읽히는 메뉴만 포함하라. 사진에 없는 메뉴를 추측하거나 지어내지 마라.
            메뉴명은 한국어 음식명만 남겨라. 외국어 병기, 수량·단위 표기, 괄호 설명 등 음식명이 아닌 부가 정보는 모두 제거하라.
            가격은 숫자만 남겨라 (통화 기호, 단위, 구분자 제거).
            메뉴가 아닌 텍스트(가게 이름, 전화번호, 안내문 등)는 제외하라.
            가격을 찾지 못했거나 확실하지 않은 메뉴는 price를 0으로 하라.

            반드시 아래 JSON 배열만 출력하라. 설명, 마크다운 코드펜스 금지.
            [{"name": "메뉴명", "price": 8000}]
        """.trimIndent()
    }
}
