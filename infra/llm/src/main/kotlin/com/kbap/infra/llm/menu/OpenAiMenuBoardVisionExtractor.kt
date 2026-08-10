package com.kbap.infra.llm.menu

import com.kbap.common.domain.metering.LlmCallCostIncurred
import com.kbap.common.port.llm.ExtractedMenu
import com.kbap.common.port.llm.MenuBoardVisionExtractor
import com.kbap.common.port.llm.OcrItem
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
        val header = """
            이 메뉴판 사진에서 메뉴명과 가격을 추출해줘. 판독은 사진만 보고 한다.
            아래는 클라이언트 화면의 항목 목록이다(형식 "idx: 텍스트"). 판독 근거가 아니라 매칭 참조표이므로 [매칭 — OCR 참조표] 규칙대로만 쓴다.
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
        private val SYSTEM_PROMPT = """
            너는 한국 식당 메뉴판 사진에서 메뉴와 가격을 추출하고, 클라이언트 화면 항목에 매칭하는 도구다. 반드시 JSON 객체 하나로만 응답한다 — 설명·마크다운·코드펜스 없이.
            형식: {"results":[{"name":"...","koreanName":"...","price":16000,"matchedIdx":0}]}

            [판독 — 사진 단독]
            메뉴명과 가격의 유일한 근거는 사진 픽셀이다. 아래 OCR 목록의 텍스트는 판독에 쓰지 않는다 — 무엇이 메뉴인지, 글자가 무엇인지 판단할 때 참고하지 마라.
            - 사진에서 읽히는 메뉴는 OCR 목록에 대응 항목이 있든 없든 전부 결과에 넣는다.
            - OCR 목록에만 있고 사진에서 확인되지 않는 텍스트는 결과에 넣지 않는다.
            - 사진에서 확실히 판독되지 않는 글자는 추측해 채우지 않고, OCR 텍스트로 대신 메우지도 않는다.
            - 표기가 낯설거나 흔한 메뉴명과 비슷해도 사진에 쓰인 대로 읽는다. 정상적으로 쓰인 한국어 메뉴명은 흔치 않아도 실재 메뉴이므로 네가 아는 더 흔한 이름으로 바꾸지 마라(예: "물냉면"→"밀냉면", "짜글이"→"찌개"로 바꾸지 않는다 — 예시는 원칙의 예시일 뿐 목록이 아니다).

            [규칙]
            - name: 사진에 표기된 그대로의 메뉴명(외국어 병기 포함).
            - koreanName: 표준 한국어 메뉴명. 사진 표기가 외국어뿐이거나 병기여도 순수 한국어로만 적는다(영문 알파벳 금지). 이 값은 서버 DB 조회 키다.
            - price: 원(KRW) 단위 정수 숫자만, 따옴표 없이(문자열이면 스캔 전체가 실패한다). 통화기호·콤마·"원" 제거(예: "54,000원"→54000). 축약은 환산한다("1.6"/"1.6만"→16000, 천원 축약 "9.0"→9000). 미표기는 null.
            - 메뉴가 아닌 텍스트(상호·전화번호·원산지·영업안내 등)는 제외한다.
            - 한 메뉴에 사이즈별 가격이 여럿이면 항목을 분리한다(예: "김치찌개(소)", "김치찌개(대)").

            [매칭 — OCR 참조표]
            아래 OCR 목록은 판독 근거가 아니라, 이미 판독을 끝낸 메뉴를 클라이언트 화면 항목에 잇기 위한 참조표다. 판독이 끝난 뒤에만 사용한다.
            - 각 메뉴를 OCR 목록의 idx 에 매칭한다. 사진 위치가 아니라 텍스트 내용으로 매칭한다(OCR 순서는 사진 배치와 다를 수 있다).
            - 한 메뉴가 여러 OCR 조각(예: "삼겹"+"살")으로 쪼개져 있으면 matchedIdx 는 그중 한 조각의 idx 하나만 준다 — 남은 조각 idx 로 별도 result 를 만들지 않는다.
            - 대응하는 OCR 항목이 없으면 matchedIdx 는 null 이다. 매칭이 안 된다고 메뉴를 결과에서 빼지 마라.
            - 한 idx 는 최대 하나의 result 에만 쓴다(중복 금지). 한 OCR 항목에 여러 메뉴가 병합돼 있거나 사이즈로 나뉘면, 그중 하나에만 그 idx 를 주고 나머지는 null.
        """.trimIndent()
    }
}
