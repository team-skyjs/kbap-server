package com.kbap.api.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.ingredient.model.IngredientCode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminFoodIngredientAuditService(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val mapper = jacksonObjectMapper()

    @Transactional(readOnly = true)
    fun auditIngredients(): AdminFoodIngredientAuditResponse {
        val rows = jdbcTemplate.query("SELECT id, ingredients FROM food ORDER BY id") { rs, _ ->
            rs.getLong(1) to rs.getString(2)?.let(mapper::readTree)
        }
        val issues = sortedMapOf<IngredientIssue, MutableList<Long>>()
        rows.forEach { (foodId, node) ->
            issuesOf(node).forEach { issues.getOrPut(it) { mutableListOf() } += foodId }
        }
        return AdminFoodIngredientAuditResponse(
            scanned = rows.size,
            nullCount = rows.count { (_, node) -> node == null || node.isNull },
            emptyCount = rows.count { (_, node) -> node != null && node.isArray && node.isEmpty },
            maxIngredientCount = rows.maxOfOrNull { (_, node) -> node?.takeIf { it.isArray }?.size() ?: 0 } ?: 0,
            issues = issues.mapKeys { it.key.name },
        )
    }

    private fun issuesOf(node: JsonNode?): Set<IngredientIssue> {
        if (node == null || node.isNull) return emptySet()
        if (!node.isArray) return setOf(IngredientIssue.NOT_ARRAY)
        val found = sortedSetOf<IngredientIssue>()
        val codes = mutableListOf<String>()
        node.forEach { item ->
            val code = item.path("code")
            val percent = item.path("inclusion_percent")
            if (!item.isObject || !code.isTextual || !percent.isIntegralNumber) {
                found += IngredientIssue.MALFORMED_ITEM
                return@forEach
            }
            codes += code.asText()
            when {
                !CODE_FORMAT.matches(code.asText()) -> found += IngredientIssue.INVALID_CODE_FORMAT
                code.asText() !in KNOWN_CODES -> found += IngredientIssue.UNKNOWN_CODE
            }
            when {
                percent.asInt() == 0 -> found += IngredientIssue.ZERO_PERCENT
                percent.asInt() !in 1..100 -> found += IngredientIssue.OUT_OF_RANGE
            }
        }
        if (codes.size != codes.toSet().size) found += IngredientIssue.DUPLICATE_CODE
        if (node.size() > Food.MAX_INGREDIENTS) found += IngredientIssue.TOO_MANY
        return found
    }

    private companion object {
        val CODE_FORMAT = Regex("^[A-Z][A-Z0-9_]*$")
        val KNOWN_CODES = IngredientCode.entries.map { it.name }.toSet()
    }
}

enum class IngredientIssue {
    NOT_ARRAY,
    MALFORMED_ITEM,
    INVALID_CODE_FORMAT,
    UNKNOWN_CODE,
    ZERO_PERCENT,
    OUT_OF_RANGE,
    DUPLICATE_CODE,
    TOO_MANY,
}

data class AdminFoodIngredientAuditResponse(
    val scanned: Int,
    val nullCount: Int,
    val emptyCount: Int,
    val maxIngredientCount: Int,
    val issues: Map<String, List<Long>>,
)
