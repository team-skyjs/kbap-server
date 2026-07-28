package com.kbap.infra.llm.food

import com.kbap.common.domain.Spiciness
import com.kbap.common.port.llm.FoodAvoidanceAssessment
import com.kbap.common.port.llm.FoodAvoidanceAssessmentClient
import com.kbap.common.port.llm.FoodAvoidanceAssessmentResult
import com.kbap.infra.llm.client.LlmFanoutClient
import com.kbap.infra.llm.model.LlmChatRequest
import kotlin.math.roundToInt

// 안전 직결 — 유효 모델 응답이 minAgreement 미만이면 종합하지 않고 예외.
// 기본 2(단일 모델 판단 금지). 1 은 다중 모델 키 확보 전 과도기 구성용이며, 확보 후 기본값으로 복원한다.
class SpringAiFoodAvoidanceAssessmentClient(
    private val fanoutClient: LlmFanoutClient,
    private val minAgreement: Int = DEFAULT_MIN_AGREEMENT,
) : FoodAvoidanceAssessmentClient {

    init {
        require(minAgreement >= 1) { "minAgreement 는 1 이상이어야 합니다: $minAgreement" }
    }

    override fun call(koreanName: String, candidateCodes: Set<String>): FoodAvoidanceAssessmentResult {
        require(candidateCodes.isNotEmpty()) { "기피성분 조사는 후보 코드가 비어 있으면 호출할 수 없습니다" }

        val fanout = fanoutClient.generate(LlmChatRequest(prompt = promptOf(koreanName, candidateCodes), system = SYSTEM_PROMPT))
        val validResponses = fanout.successes.mapNotNull { parseValidOrNull(it.content, candidateCodes) }
        require(validResponses.size >= minAgreement) {
            "기피성분 조사를 종합할 유효 모델 응답이 부족합니다: ${validResponses.size}/${fanout.attemptedCount()} (최소 $minAgreement)"
        }
        return FoodAvoidanceAssessmentResult(
            substances = aggregateSubstances(candidateCodes, validResponses.map { it.percentByCode }),
            spiciness = validResponses.map { it.spiciness }.average().roundToInt(),
        )
    }

    private fun parseValidOrNull(raw: String, candidateCodes: Set<String>): ValidResponse? =
        try {
            val response = FoodContentJsonParser.parse<AssessmentResponse>(raw)
            if (response.spiciness !in Spiciness.RANGE) {
                null
            } else {
                val byCode = mutableMapOf<String, Int>()
                var valid = true
                for (item in response.assessments) {
                    // 후보 밖 코드는 존재하지 않는 항목 취급 — percent·중복 검사 없이 항목만 스킵(KB-236)
                    if (item.code !in candidateCodes) continue
                    if (item.inclusionPercent !in 0..100 || item.code in byCode) {
                        valid = false
                        break
                    }
                    byCode[item.code] = item.inclusionPercent
                }
                if (valid) ValidResponse(byCode, response.spiciness) else null
            }
        } catch (e: FoodContentParseException) {
            null
        }

    private fun aggregateSubstances(candidateCodes: Set<String>, responses: List<Map<String, Int>>): List<FoodAvoidanceAssessment> =
        candidateCodes.mapNotNull { code ->
            val avg = responses.map { it[code] ?: 0 }.average().roundToInt()
            if (avg == 0) null else FoodAvoidanceAssessment(code, avg)
        }

    private data class ValidResponse(val percentByCode: Map<String, Int>, val spiciness: Int)

    private fun promptOf(koreanName: String, candidateCodes: Set<String>): String =
        """
        너는 한국 음식 레시피와 알레르기·기피성분 전문가다. 아래 메뉴의 대표 레시피를 기준으로
        기피성분의 포함 확률을 1~100 정수로 매기고, 음식의 맵기를 0~10 정수로 판정하라.
        음식명: "$koreanName"

        # spiciness (맵기) 의 의미
        - 0: 맵지 않음 (계란말이, 치즈볼 등)
        - 1~3: 약간 매콤 (제육볶음 순한맛, 김치찌개 등)
        - 4~6: 보통 매움 (떡볶이, 닭갈비 등)
        - 7~10: 매우 매움 (불닭, 매운 갈비찜, 마라 계열 등)

        # inclusionPercent 의 의미
        "손님이 아무 식당에서나 이 메뉴를 시켰을 때, 그 한 접시에 이 성분이 들어 있을 확률."
        양(量)이 아니라 포함 여부의 확률이다. 값 기준:
        - 95~100: 정의상 반드시 들어감 (김밥의 쌀, 라떼의 우유)
        - 80~95 : 표준 레시피 핵심 재료 (떡볶이의 고추장→SOY)
        - 55~80 : 대부분 넣지만 집집마다 다름 (김밥의 계란)
        - 30~55 : 흔한 선택 재료·고명·양념 (부침의 쪽파)
        - 10~30 : 일부 식당·지역·변형에서만 (감자탕의 들깨)
        - 1~10  : 미량·교차오염·드문 변형
        present(들어갈 가능성 있는)만 나열한다. 사실상 0%인 성분은 뺀다. 애매하면 낮은 값으로
        포함하되, 5 미만이면 대개 생략. 기피성분이 사실상 없는 메뉴(아메리카노, 공기밥 등)는
        빈 배열([])을 반환한다.

        # 반드시 추적할 숨은·파생 성분 (겉에 안 보여도 양념·육수·가공품 속에 있음)
        - 양조간장(거의 모든 볶음·조림·양념): SOY 90+, WHEAT 75+ (한국 양조간장엔 밀)
        - 고추장·된장·쌈장·춘장: SOY 90+
        - 어묵·맛살·게맛살·크래미: FISH 90+, WHEAT 70+
        - 멸치육수·다시: ANCHOVY 60~85, FISH 70+, DASHI 40+
        - 사골·고기육수: BEEF 또는 PORK 70+, BROTH
        - 액젓·멸치액젓(김치·양념): FISH_SAUCE 80+, ANCHOVY, FISH
        - 새우젓(돼지·순대·만두양념): SALTED_SHRIMP, SHRIMP
        - 굴소스: OYSTER_SAUCE, OYSTER
        - 밀가루 반죽·튀김옷·부침·면·빵·만두피: WHEAT 90+
        - 튀김옷·부침 반죽: EGG 40~70
        - 마요네즈(샐러드·핫도그·양념): EGG 85+
        - 치즈: MILK 95, DAIRY 95, CHEESE 95, RENNET 40
        - 버터·크림·우유·라떼: MILK/DAIRY 95+, 버터일 때 BUTTER
        - 카라멜소스·연유: MILK/DAIRY
        - 떡볶이·라볶이의 떡: 밀떡 흔함 → WHEAT 40~70
        - 소시지·햄·베이컨·스팸: PORK 90+
        - 맥주·매실주·청주·막걸리: ALCOHOL 100 (맥주엔 BARLEY·WHEAT, 막걸리엔 WHEAT 흔함)
        - 미림·맛술: MIRIN, ALCOHOL, COOKING_WINE
        - 김치(반찬·찌개·볶음밥): FISH_SAUCE 70+, SALTED_SHRIMP 50+, GARLIC, SCALLION
        - 파·대파·쪽파·마늘·양파: SCALLION/GARLIC/ONION, 한식 양념 베이스 대개 60~90
        - 참기름·깨소금(거의 모든 한식 마무리): SESAME 70~95

        # 후보 성분 코드 (이 목록 밖 code 절대 금지)
        ${candidateCodes.joinToString(" ")}
        출력의 모든 code 는 반드시 위 후보 목록 안에 있어야 한다. 위 휴리스틱이 가리키는 성분이라도
        후보 목록에 없으면 절대 출력하지 마라(확률이 높아도 뺀다).

        # 규칙
        - 음식명에 명백한 오탈자가 보이면 가장 유사한 실제 한식 메뉴로 추론해 판단하라
          (김치찌게 → 김치찌개). 단 어떤 음식인지 애매하면 지어내지 말고 이름 그대로 보수적으로
          판단하라 — 안전 데이터이므로 잘못된 추론이 누락보다 위험하다.
        - SEAFOOD/FISH/POULTRY 는 총칭이다. 구체 종(SHRIMP, SALMON, CHICKEN 등)을 넣을 땐
          총칭도 함께 넣되, 총칭 확률 ≥ 구체 종 확률이 되게 하라.
        - ASAFOETIDA, LUPIN, GHEE, GOAT_MILK, RYE, BRAZIL_NUT 등은 한식에 거의 없다.
          근거 없이 넣지 마라.
        - 확신 없는 성분은 지어내지 말고 낮은 값으로 두거나 생략하라.
        - 같은 code 를 중복하지 마라.

        # 출력 형식 (JSON 만, 다른 텍스트·마크다운·코드펜스 금지 — spiciness 필수)
        {"assessments": [{"code": "WHEAT", "inclusionPercent": 95}, {"code": "SESAME", "inclusionPercent": 80}], "spiciness": 4}
        """.trimIndent()

    data class AssessmentResponse(
        val assessments: List<AssessmentItem> = emptyList(),
        val spiciness: Int = -1,
    )

    data class AssessmentItem(val code: String = "", val inclusionPercent: Int = -1)

    companion object {
        private const val DEFAULT_MIN_AGREEMENT = 2
        private const val SYSTEM_PROMPT =
            "너는 한국 음식 레시피와 알레르기·기피성분 전문가다. 반드시 지정된 JSON 형식으로만 답한다."
    }
}
