package com.meogo.core.research.parse

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.lang.LocalizedText
import com.meogo.core.research.input.CandidateSubstance
import com.meogo.core.research.input.ScoringFood

class ScoringResponseParser {

    private val objectMapper = jacksonObjectMapper()

    private val languageByCode = LanguageCode.entries.associateBy { it.code }

    fun parse(
        content: String,
        foods: List<ScoringFood>,
        candidates: List<CandidateSubstance>,
    ): ModelScoring {
        val root = readRoot(content)
        val resultsNode = root.get("results")
        if (resultsNode == null || !resultsNode.isArray) {
            throw ScoringResponseParseException("research.scoringResponse.results 가 없거나 배열이 아닙니다")
        }
        val foodIdByName = foods.associate { it.koreanName to it.foodId }
        val candidateCodes = candidates.map { it.code }.toSet()

        val included = mutableMapOf<Long, List<SubstanceJudgement>>()
        val nameTranslations = mutableMapOf<Long, Map<LanguageCode, String>>()
        val descriptions = mutableMapOf<Long, LocalizedText>()
        val coveredFoodIds = mutableSetOf<Long>()

        for (resultNode in resultsNode) {
            val foodId = foodIdByName[resultNode.path("food").asText(null)] ?: continue
            coveredFoodIds.add(foodId)

            val existing = included[foodId].orEmpty()
            val judgements = parseJudgements(
                resultNode.path("included"),
                candidateCodes,
                existing.map { it.code }.toMutableSet(),
            )
            if (judgements.isNotEmpty()) {
                included[foodId] = existing + judgements
            }

            val translations = parseTranslations(resultNode.get("nameTranslations"))
            if (translations.isNotEmpty() && foodId !in nameTranslations) {
                nameTranslations[foodId] = translations
            }

            parseDescription(resultNode.get("description"))?.let { descriptions.putIfAbsent(foodId, it) }
        }

        return ModelScoring(
            included = included,
            nameTranslations = nameTranslations,
            descriptions = descriptions,
            coveredFoodIds = coveredFoodIds,
        )
    }

    private fun readRoot(content: String): JsonNode {
        val stripped = stripCodeFence(content)
        if (stripped.isBlank()) {
            throw ScoringResponseParseException("research.scoringResponse.content 가 비어있습니다")
        }
        return try {
            objectMapper.readTree(stripped) ?: throw ScoringResponseParseException("research.scoringResponse.content 를 파싱할 수 없습니다")
        } catch (exception: JacksonException) {
            throw ScoringResponseParseException("research.scoringResponse.content 를 파싱할 수 없습니다")
        }
    }

    private fun stripCodeFence(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```")) {
            return trimmed
        }
        return trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun parseJudgements(
        includedNode: JsonNode,
        candidateCodes: Set<String>,
        seenCodes: MutableSet<String>,
    ): List<SubstanceJudgement> {
        val judgements = mutableListOf<SubstanceJudgement>()
        for (itemNode in includedNode) {
            val code = itemNode.path("code").asText(null) ?: continue
            if (code !in candidateCodes || code in seenCodes) {
                continue
            }
            val scoreNode = itemNode.get("score")
            val probabilityNode = itemNode.get("probability")
            if (scoreNode == null || !scoreNode.isIntegralNumber || probabilityNode == null || !probabilityNode.isIntegralNumber) {
                continue
            }
            val score = scoreNode.intValue()
            val probability = probabilityNode.intValue()
            if (score !in 0..2 || probability !in 1..100) {
                continue
            }
            judgements.add(SubstanceJudgement(code = code, score = score, probability = probability))
            seenCodes.add(code)
        }
        return judgements
    }

    private fun parseTranslations(translationsNode: JsonNode?): Map<LanguageCode, String> {
        if (translationsNode == null || !translationsNode.isObject) {
            return emptyMap()
        }
        val translations = mutableMapOf<LanguageCode, String>()
        for ((key, valueNode) in translationsNode.properties()) {
            val language = languageByCode[key] ?: continue
            if (language == LanguageCode.KO) {
                continue
            }
            val value = valueNode.asText(null)?.takeIf { it.isNotBlank() } ?: continue
            translations[language] = truncate(value)
        }
        return translations
    }

    private fun parseDescription(descriptionNode: JsonNode?): LocalizedText? {
        if (descriptionNode == null || !descriptionNode.isObject) {
            return null
        }
        val korean = descriptionNode.path("ko").asText(null)?.takeIf { it.isNotBlank() } ?: return null
        return LocalizedText(
            korean = truncate(korean),
            translations = parseTranslations(descriptionNode.get("translations")),
        )
    }

    private fun truncate(value: String): String =
        if (value.length > MAX_LENGTH) value.take(MAX_LENGTH) else value

    companion object {
        private const val MAX_LENGTH = 230
    }
}
