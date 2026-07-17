package com.kbap.infra.llm.menu

import com.kbap.core.llm.LlmCallCostIncurred
import com.kbap.core.scan.ExtractedMenu
import com.kbap.core.scan.MenuBoardVisionExtractor
import com.kbap.core.scan.OcrItem
import com.kbap.infra.llm.model.LlmPricing
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

// 메뉴판 사진 URL 을 gpt-4o-mini vision 에 넘겨 메뉴명·가격을 추출하고 클라이언트 OCR idx 에 매칭한다(KB-138).
// 이미지 바이트는 서버를 거치지 않는다 — 모델이 URL(imageBaseUrl + path)을 직접 fetch 한다.
class OpenAiMenuBoardVisionExtractor(
    private val chatModel: ChatModel,
    private val parser: MenuBoardResultParser,
    private val imageBaseUrl: String,
    private val pricing: LlmPricing = LlmPricing.UNPRICED,
    private val configuredModelName: String = "",
    private val eventPublisher: ApplicationEventPublisher = ApplicationEventPublisher { },
) : MenuBoardVisionExtractor {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun extract(imagePath: String, ocrItems: List<OcrItem>): List<ExtractedMenu> {
        val imageUrl = URI.create("${imageBaseUrl.trimEnd('/')}/${imagePath.trimStart('/')}")
        val media = Media.builder().mimeType(mimeTypeOf(imagePath)).data(imageUrl).build()
        val userMessage = UserMessage.builder().text(userPromptWith(ocrItems)).media(media).build()

        val response = chatModel.call(Prompt(listOf(SystemMessage(SYSTEM_PROMPT), userMessage)))
        val cost = costIncurredFrom(response)
        publishCost(cost)
        logTokenUsage(cost, response.metadata.usage.totalTokens)
        val raw = response.results.firstOrNull()?.output?.text.orEmpty()
        return parser.parse(raw)
    }

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
        val ocrLines = ocrItems.joinToString("\n") { "${it.idx}: ${it.rawMenuName}" }
        return "이 메뉴판 사진에서 메뉴명과 가격을 추출해줘.\n" +
            "아래는 클라이언트가 같은 사진을 OCR 한 결과다(형식 \"idx: 텍스트\").\n" +
            "각 추출 메뉴에 대응하는 OCR 항목의 idx 를 matchedIdx 에 넣고, 대응하는 OCR 항목이 없으면 matchedIdx 를 null 로 둬라.\n" +
            "OCR:\n$ocrLines"
    }

    private fun mimeTypeOf(path: String): MimeType =
        when (path.substringAfterLast('.', "").lowercase()) {
            "png" -> MimeTypeUtils.IMAGE_PNG
            "webp" -> MimeType.valueOf("image/webp")
            "gif" -> MimeTypeUtils.IMAGE_GIF
            else -> MimeTypeUtils.IMAGE_JPEG
        }

    companion object {
        private const val SYSTEM_PROMPT =
            "너는 한국 식당 메뉴판 사진에서 메뉴와 가격을 추출하고, 클라이언트 OCR 항목에 매칭하는 도구다. 반드시 JSON 객체 하나로만 응답한다.\n" +
                "형식: {\"results\":[{\"name\":\"...\",\"koreanName\":\"...\",\"price\":16000,\"matchedIdx\":0}]}\n" +
                "규칙:\n" +
                "- name: 사진에 표기된 그대로의 메뉴명(외국어 병기 포함).\n" +
                "- koreanName: 표준 한국어 메뉴명으로 정제한 이름. 사진 표기가 외국어뿐이어도 한국어 메뉴명으로 적는다.\n" +
                "- matchedIdx: 이 메뉴에 대응하는 클라이언트 OCR 항목의 idx(정수). 사진 속 위치·텍스트로 판단한다. 대응하는 OCR 항목이 없으면 null.\n" +
                "- 메뉴가 아닌 텍스트(상호·전화번호·원산지 표기·영업 안내 재료정보 등)는 results 에서 제외한다.\n" +
                "- price: 반드시 원(KRW) 단위 정수 숫자만. 통화기호·콤마·\"원\" 제거. 예: \"54,000원\" → 54000.\n" +
                "  축약 표기는 환산한다: \"1.6\" 또는 \"1.6만\" → 16000, \"9.0\"/\"9,0\" 처럼 천원 축약이면 → 9000. 메뉴판 전체 가격대 문맥으로 단위를 판단한다.\n" +
                "  가격 미표기 메뉴는 null.\n" +
                "- 한 메뉴에 사이즈별 가격이 여러 개면 항목을 분리한다(예: \"김치찌개(소)\", \"김치찌개(대)\")."
    }
}
