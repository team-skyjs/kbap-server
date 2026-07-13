package com.meogo.domain.research.parse

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.meogo.core.lang.LanguageCode
import com.meogo.domain.research.input.CandidateSubstance
import com.meogo.domain.research.input.ScoringFood

class ScoringResponseParser {

    private val objectMapper = jacksonObjectMapper()

    fun parse(
        content: String,
        foods: List<ScoringFood>,
        candidates: List<CandidateSubstance>,
    ): ModelScoring {
        val root = readRoot(content)
        val judgementsNode = root.get("r")
        if (judgementsNode == null || !judgementsNode.isArray) {
            throw ScoringResponseParseException("research.scoringResponse.r 가 없거나 배열이 아닙니다")
        }

        val included = mutableMapOf<Long, MutableList<SubstanceJudgement>>()
        val coveredFoodIds = mutableSetOf<Long>()
        val acceptedPairs = mutableSetOf<Long>()

        for (itemNode in judgementsNode) {
            val values = readIntArray(itemNode, JUDGEMENT_ARITY) ?: continue
            val (foodIndex, substanceIndex, score, probability) = values
            val food = foods.getOrNull(foodIndex) ?: continue
            val candidate = candidates.getOrNull(substanceIndex) ?: continue
            if (score !in 0..2 || probability !in 1..100) {
                continue
            }
            val pairKey = foodIndex.toLong() * candidates.size + substanceIndex
            if (!acceptedPairs.add(pairKey)) {
                continue
            }
            coveredFoodIds.add(food.foodId)
            included.getOrPut(food.foodId) { mutableListOf() }
                .add(SubstanceJudgement(code = candidate.code, score = score, probability = probability))
        }

        coveredFoodIds += readAttestedFoodIds(root.get("c"), foods)

        return ModelScoring(
            included = included,
            nameTranslations = readNameTranslations(root.get("t"), foods),
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

    private fun readIntArray(node: JsonNode, arity: Int): List<Int>? {
        if (!node.isArray || node.size() != arity) {
            return null
        }
        val values = ArrayList<Int>(arity)
        for (element in node) {
            if (!element.isIntegralNumber) {
                return null
            }
            values.add(element.intValue())
        }
        return values
    }

    private fun readAttestedFoodIds(attestNode: JsonNode?, foods: List<ScoringFood>): Set<Long> {
        if (attestNode == null || !attestNode.isArray) {
            return emptySet()
        }
        val attested = mutableSetOf<Long>()
        for (element in attestNode) {
            if (!element.isIntegralNumber) {
                continue
            }
            foods.getOrNull(element.intValue())?.let { attested.add(it.foodId) }
        }
        return attested
    }

    private fun readNameTranslations(
        translationsNode: JsonNode?,
        foods: List<ScoringFood>,
    ): Map<Long, Map<LanguageCode, String>> {
        if (translationsNode == null || !translationsNode.isArray) {
            return emptyMap()
        }
        val nameTranslations = mutableMapOf<Long, Map<LanguageCode, String>>()
        val claimedFoodIndices = mutableSetOf<Int>()
        for (itemNode in translationsNode) {
            if (!itemNode.isArray || itemNode.size() < 2) {
                continue
            }
            val foodIndexNode = itemNode.get(0)
            if (!foodIndexNode.isIntegralNumber) {
                continue
            }
            val foodIndex = foodIndexNode.intValue()
            val food = foods.getOrNull(foodIndex) ?: continue
            if (!claimedFoodIndices.add(foodIndex)) {
                continue
            }
            val valuesNode = itemNode.get(1)
            if (!valuesNode.isArray) {
                continue
            }
            val translations = readTranslationValues(valuesNode)
            if (translations.isNotEmpty()) {
                nameTranslations[food.foodId] = translations
            }
        }
        return nameTranslations
    }

    private fun readTranslationValues(valuesNode: JsonNode): Map<LanguageCode, String> {
        val translations = mutableMapOf<LanguageCode, String>()
        val limit = minOf(valuesNode.size(), TRANSLATION_LANGUAGES.size)
        for (position in 0 until limit) {
            val value = valuesNode.get(position).asText(null)?.takeIf { it.isNotBlank() } ?: continue
            translations[TRANSLATION_LANGUAGES[position]] = value
        }
        return translations
    }

    companion object {
        private const val JUDGEMENT_ARITY = 4
        private val TRANSLATION_LANGUAGES = LanguageCode.entries.filter { it != LanguageCode.KO }
    }
}
