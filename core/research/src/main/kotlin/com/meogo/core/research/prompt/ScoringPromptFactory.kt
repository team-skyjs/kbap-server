package com.meogo.core.research.prompt

import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.research.input.CandidateSubstance
import com.meogo.core.research.input.ScoringFood

class ScoringPromptFactory {

    fun build(
        foods: List<ScoringFood>,
        candidates: List<CandidateSubstance>,
        includeNameTranslations: Boolean = false,
    ): ScoringPrompt {
        require(foods.isNotEmpty()) { "research.scoringPrompt.foods 는 비어있을 수 없습니다" }

        val candidateLines = candidates
            .mapIndexed { index, candidate -> "$index:${candidate.code}" }
            .joinToString("\n")
        val foodLines = foods
            .mapIndexed { index, food -> "$index:${food.koreanName}" }
            .joinToString("\n")
        val translationLanguageOrder = TRANSLATION_LANGUAGES.joinToString(", ") { it.code }

        // 이 파일은 프로젝트의 "Kotlin 주석 금지" 규약의 명시적 예외다(사용자 승인).
        // LLM 프롬프트를 영어로 전환하면서, 각 지시문의 한국어 번역을 주석으로 병기해 유지보수한다.
        val system = buildString {
            // 당신은 각 후보 기피성분이 각 음식에 대표(표준) 레시피 기준으로 포함되는지 판단하는 분석기다.
            appendLine("You are an analyzer that judges whether each candidate avoidance substance is included in each food, based on the food's representative (standard) recipe.")
            // 포함된다고 판단한 (음식, 성분) 쌍만 응답하고, 미포함 쌍은 생략한다.
            appendLine("Respond only with the (food, substance) pairs you judge as included; omit pairs judged as not included.")
            // 각 판단 쌍은 score(0/1/2 — 0=낮음, 1=가능성 있음, 2=높음)와 probability(정수 1-100)를 가진다.
            appendLine("Each judged pair has a score (0/1/2 — 0=low, 1=possible, 2=high) and a probability (an integer from 1-100).")
            // 음식과 성분은 아래 목록의 0-base 인덱스로 지칭한다.
            appendLine("Refer to foods and substances by their 0-based index in the lists below.")
            // 아래 스키마의 원시 JSON 만 출력한다 — 코드펜스·설명 금지.
            appendLine("Output raw JSON only in this exact schema — no code fences, no explanation:")
            appendLine("{\"c\":[food indices you judged],\"r\":[[food index, substance index, score, probability], ...]}")
            // "c" 에는 판단을 완료한 음식 인덱스를 전부 나열한다(포함 성분이 없어도).
            appendLine("\"c\" MUST list the index of EVERY food you judged, even if it has no included substances.")
            // "r" 에는 포함 쌍만 담고, score 는 0/1/2 중 하나, probability 는 정수 1-100 이다.
            appendLine("\"r\" contains only included pairs; score is one of 0/1/2 and probability is an integer from 1-100.")
            if (includeNameTranslations) {
                // 각 판단 음식의 이름을 아래 고정 언어 순서로 번역한다(한국어 제외).
                appendLine("Also translate each judged food's name into these languages in this exact fixed order: $translationLanguageOrder. Do not translate into Korean.")
                // 번역은 "t" 배열에 담는다 — [음식 인덱스, [위 순서의 9개 번역]].
                appendLine("Add a \"t\" array: [[food index, [9 translations in the fixed order above]], ...].")
            }
            appendLine()
            appendLine("[Candidate avoidance substances - index:CODE]")
            append(candidateLines)
        }

        val prompt = buildString {
            appendLine("[Foods to judge - index:name]")
            append(foodLines)
        }

        return ScoringPrompt(prompt = prompt, system = system)
    }

    companion object {
        private val TRANSLATION_LANGUAGES = LanguageCode.entries.filter { it != LanguageCode.KO }
    }
}
