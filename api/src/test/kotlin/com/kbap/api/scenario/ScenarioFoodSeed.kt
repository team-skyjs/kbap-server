package com.kbap.api.scenario

import java.sql.Connection
import java.sql.Statement
import javax.sql.DataSource

object ScenarioFoodSeed {
    fun ensureFood(
        dataSource: DataSource,
        koreanName: String,
        spiciness: Int,
        substances: Map<String, Int>,
    ): Long = dataSource.connection.use { connection ->
        findFoodId(connection, koreanName) ?: insertFood(connection, koreanName, spiciness, substances)
    }

    private fun findFoodId(connection: Connection, koreanName: String): Long? =
        connection.prepareStatement("SELECT id FROM food WHERE korean_name = ?").use { ps ->
            ps.setString(1, koreanName)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    private fun insertFood(
        connection: Connection,
        koreanName: String,
        spiciness: Int,
        substances: Map<String, Int>,
    ): Long {
        substances.keys.forEach { code -> ensureSubstance(connection, code) }
        val ingredientsJson = substances.entries.joinToString(separator = ",", prefix = "[", postfix = "]") {
            """{"code":"${it.key}","inclusion_percent":${it.value}}"""
        }
        return connection.prepareStatement(
            "INSERT INTO food (korean_name, display_name, image_ref, description, name_translations, description_translations, " +
                "ingredients, spiciness, content_status, status, created_at, updated_at) " +
                "VALUES (?, ?, NULL, ?, '{}', '{}', ?, ?, 'READY', 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
            Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setString(1, koreanName)
            ps.setString(2, koreanName)
            ps.setString(3, "$koreanName 시나리오 설명")
            ps.setString(4, ingredientsJson)
            ps.setInt(5, spiciness)
            ps.executeUpdate()
            ps.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
        }
    }

    private fun ensureSubstance(connection: Connection, code: String) {
        val exists = connection.prepareStatement("SELECT 1 FROM ingredients WHERE code = ?").use { ps ->
            ps.setString(1, code)
            ps.executeQuery().use { it.next() }
        }
        if (exists) return
        connection.prepareStatement(
            "INSERT INTO ingredients (code, korean_name, translations, status, created_at, updated_at) " +
                "VALUES (?, ?, '{}', 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
        ).use { ps ->
            ps.setString(1, code)
            ps.setString(2, "시나리오성분-$code")
            ps.executeUpdate()
        }
    }
}
