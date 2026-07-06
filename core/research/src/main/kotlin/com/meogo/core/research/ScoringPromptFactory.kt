package com.meogo.core.research

import com.meogo.core.kernel.lang.LanguageCode

class ScoringPromptFactory {

    fun build(foods: List<ScoringFood>, candidates: List<CandidateSubstance>): ScoringPrompt {
        require(foods.isNotEmpty()) { "research.scoringPrompt.foods 는 비어있을 수 없습니다" }

        val foodLines = foods.joinToString("\n") { "- ${it.koreanName}" }
        val candidateLines = candidates.joinToString("\n") { "- ${it.code} (${it.koreanLabel})" }
        val targetLanguages = LanguageCode.entries
            .filter { it != LanguageCode.KO }
            .joinToString(", ") { it.code }

        val system = buildString {
            appendLine("당신은 음식의 대표 레시피 기준으로 주어진 후보 기피성분의 포함 가능성을 판단하는 분석기다.")
            appendLine("특정 식당이 아니라 일반적으로 알려진 대표 조리 방식을 기준으로 판단한다.")
            appendLine("포함된다고 판단한 (음식, 성분)만 응답하고, 미포함은 생략한다.")
            appendLine("각 판단은 score(0=낮음, 1=가능성 있음, 2=높음)와 probability(정수 1~100)를 가진다.")
            appendLine("probability 는 반드시 1 이상 100 이하의 정수여야 한다.")
            appendLine("후보에 없는 성분코드나 목록에 없는 음식은 응답에 넣지 않는다. 오직 JSON 만 출력한다.")
        }.trimEnd()

        val prompt = buildString {
            appendLine("[대상 음식 목록(한국어명)]")
            appendLine(foodLines)
            appendLine()
            appendLine("[후보 기피성분 목록(코드 + 한국어명)]")
            appendLine(candidateLines)
            appendLine()
            appendLine("[판단 지시]")
            appendLine("대표 레시피 기준으로 각 음식에 포함된다고 판단한 후보 성분만 응답한다.")
            appendLine("각 항목은 code(후보 코드 그대로), score(0/1/2), probability(정수 1~100)를 담는다.")
            appendLine()
            appendLine("[음식명 번역 지시]")
            appendLine("각 음식에 대해 대상 9개 언어($targetLanguages)로 번역명을 nameTranslations 에 담는다.")
            appendLine("ko 키는 넣지 않는다(원문). 모르는 언어는 키를 생략한다.")
            appendLine()
            appendLine("[음식 설명 지시]")
            appendLine("각 음식에 대해 description.ko(한국어 설명 생성)와 description.translations(대상 9개 언어 번역)를 담는다.")
            appendLine("각 설명은 공백 포함 200자를 목표로 하며 최대 230자를 넘지 않는다.")
            appendLine()
            appendLine("[출력 JSON 스키마]")
            appendLine("{")
            appendLine("  \"results\": [")
            appendLine("    {")
            appendLine("      \"food\": \"<주어진 한국어 음식명 그대로>\",")
            appendLine("      \"included\": [ { \"code\": \"EGG\", \"score\": 2, \"probability\": 90 } ],")
            appendLine("      \"nameTranslations\": { \"en\": \"...\", \"ja\": \"...\" },")
            appendLine("      \"description\": { \"ko\": \"...\", \"translations\": { \"en\": \"...\" } }")
            appendLine("    }")
            appendLine("  ]")
            append("}")
        }

        return ScoringPrompt(prompt = prompt, system = system)
    }
}
