package com.meogo.core.research.prompt

import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.research.input.CandidateSubstance
import com.meogo.core.research.input.ScoringFood

class ScoringPromptFactory {

    fun build(foods: List<ScoringFood>, candidates: List<CandidateSubstance>): ScoringPrompt {
        require(foods.isNotEmpty()) { "research.scoringPrompt.foods 는 비어있을 수 없습니다" }

        val foodLines = foods.joinToString("\n") { "- ${it.koreanName}" }
        val candidateLines = candidates.joinToString("\n") { "- ${it.code} (${it.koreanLabel})" }
        val targetLanguages = LanguageCode.entries
            .filter { it != LanguageCode.KO }
            .joinToString(", ") { it.code }

        // 이 파일은 프로젝트의 "Kotlin 주석 금지" 규약의 명시적 예외다(사용자 승인).
        // LLM 프롬프트를 영어로 전환하면서, 각 지시문의 한국어 번역을 주석으로 병기해 유지보수한다.
        val system = buildString {
            // 당신은 각 후보 기피성분이 각 음식에 포함되는지를, 그 음식의 대표(가장 널리 알려진) 레시피 기준으로 판단하는 분석기다.
            appendLine("You are an analyzer that judges whether each candidate avoidance substance is included in each food, based on the food's representative recipe.")
            // 특정 식당의 변형이 아니라 일반적으로 알려진 표준 조리 방식을 기준으로 판단한다.
            appendLine("Judge by the commonly known standard recipe, not by any specific restaurant's variation.")
            // 포함된다고 판단한 (음식, 성분) 쌍만 응답하고, 미포함으로 판단한 쌍은 생략한다.
            appendLine("Respond only with the (food, substance) pairs you judge as included; omit pairs judged as not included.")
            // 각 판단은 score(0=낮음, 1=가능성 있음, 2=높음)와 probability(정수 1~100)를 가진다.
            appendLine("Each judgement has a score (0=low, 1=possible, 2=high) and a probability (an integer from 1-100).")
            // probability 는 반드시 1 이상 100 이하의 정수여야 한다.
            appendLine("probability MUST be an integer between 1 and 100 inclusive.")
            // [코드 강제] "code" 값은 반드시 아래 후보 목록의 코드를 그대로(verbatim) 복사한다.
            // 코드를 창작·번역·변형하지 않는다. 목록에 없는 코드는 전부 폐기된다.
            appendLine("STRICT CODE RULE: every \"code\" value MUST be copied verbatim from the candidate list. NEVER invent, translate, or modify a code. Any code not in the candidate list will be discarded.")
            // [음식 강제] "food" 값은 반드시 주어진 음식 목록의 한국어명을 그대로 복사한다. 목록에 없는 음식은 넣지 않는다.
            appendLine("STRICT FOOD RULE: every \"food\" value MUST be copied verbatim from the given food list (Korean name). Do not add foods that are not in the list.")
            // [커버리지 강제] results 에는 주어진 모든 음식의 entry 가 정확히 하나씩 있어야 한다.
            // 포함 성분이 없는 음식도 entry 를 생략하지 말고 "included": [] 로 낸다.
            appendLine("STRICT COVERAGE RULE: \"results\" MUST contain exactly one entry for EVERY food in the given list. If a food has no included candidate substances, still emit its entry with \"included\": [].")
            // [출력 강제] 원시 JSON 만 출력한다 — 마크다운 코드펜스(```), 주석, 설명, 앞뒤 잡음 텍스트 금지.
            append("STRICT OUTPUT RULE: output raw JSON only. Do NOT wrap it in markdown code fences (```), and do NOT add comments, explanations, or any surrounding text.")
        }

        val prompt = buildString {
            // [대상 음식 목록(한국어명)]
            appendLine("[Target foods (Korean names)]")
            appendLine(foodLines)
            appendLine()
            // [후보 기피성분 목록(코드 + 한국어명)]
            appendLine("[Candidate avoidance substances (code + Korean label)]")
            appendLine(candidateLines)
            appendLine()
            appendLine("[Scoring instructions]")
            // 대표 레시피 기준으로 각 음식에 포함된다고 판단한 후보 성분만 응답한다.
            appendLine("For each food, respond only with the candidate substances you judge as included, based on the representative recipe.")
            // 각 항목은 code(후보 코드 그대로), score(0/1/2), probability(정수 1~100)를 반드시 담는다.
            appendLine("Each item MUST contain: code (verbatim candidate code), score (0/1/2), and probability (an integer from 1-100).")
            appendLine()
            appendLine("[Food name translation instructions - MANDATORY]")
            // 각 음식의 nameTranslations 에는 아래 9개 대상 언어를 전부 담아야 한다.
            appendLine("For each food, \"nameTranslations\" MUST contain ALL 9 target languages: $targetLanguages.")
            // 어떤 언어도 생략하지 않는다. "ko" 키는 넣지 않는다(원문이 한국어).
            appendLine("Do NOT omit any language. Do NOT include a \"ko\" key (the source name is already Korean).")
            // 언어 키는 위 코드를 정확히 그대로 사용한다 — "en_name" 같은 다른 키 형식 금지.
            appendLine("Use exactly these language codes as JSON keys. Do NOT use any other key format (e.g. \"en_name\").")
            appendLine()
            appendLine("[Food description instructions - MANDATORY]")
            // 각 음식의 description 은 ko(한국어 설명 생성)와 translations(위 9개 언어 전부)를 반드시 담는다.
            appendLine("For each food, \"description\" MUST contain \"ko\" (a Korean description you generate) and \"translations\" containing ALL 9 target languages above.")
            // 각 설명은 공백 포함 200자를 목표로 하며 최대 230자를 넘지 않는다.
            appendLine("Each description should target 200 characters including spaces and MUST NOT exceed 230 characters.")
            // "en_description" 같은 키를 만들지 말고 아래 스키마를 정확히 따른다.
            appendLine("Do NOT create keys like \"en_description\"; follow the schema below exactly.")
            appendLine()
            // [출력 JSON 스키마 — 정확히 따를 것]
            appendLine("[Output JSON schema - follow exactly]")
            appendLine("{")
            appendLine("  \"results\": [")
            appendLine("    {")
            appendLine("      \"food\": \"<the given Korean food name, verbatim>\",")
            appendLine("      \"included\": [ { \"code\": \"EGG\", \"score\": 2, \"probability\": 90 } ],")
            appendLine("      \"nameTranslations\": { \"zh-Hans\": \"...\", \"en\": \"...\", \"ja\": \"...\", \"zh-Hant\": \"...\", \"vi\": \"...\", \"id\": \"...\", \"th\": \"...\", \"ru\": \"...\", \"es\": \"...\" },")
            appendLine("      \"description\": { \"ko\": \"...\", \"translations\": { \"zh-Hans\": \"...\", \"en\": \"...\", \"ja\": \"...\", \"zh-Hant\": \"...\", \"vi\": \"...\", \"id\": \"...\", \"th\": \"...\", \"ru\": \"...\", \"es\": \"...\" } }")
            appendLine("    }")
            appendLine("  ]")
            append("}")
        }

        return ScoringPrompt(prompt = prompt, system = system)
    }
}
