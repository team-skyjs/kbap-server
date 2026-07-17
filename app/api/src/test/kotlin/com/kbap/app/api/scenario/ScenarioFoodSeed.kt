package com.kbap.app.api.scenario

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
        val foodId = connection.prepareStatement(
            "INSERT INTO food (korean_name, image_ref, description, name_translations, description_translations, " +
                "spiciness, content_status, status, created_at, updated_at) " +
                "VALUES (?, NULL, ?, '{}', '{}', ?, 'READY', 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
            Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setString(1, koreanName)
            ps.setString(2, "$koreanName 시나리오 설명")
            ps.setInt(3, spiciness)
            ps.executeUpdate()
            ps.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
        }
        substances.forEach { (code, percent) ->
            ensureSubstance(connection, code)
            connection.prepareStatement(
                "INSERT INTO food_avoidance_substance (food_id, substance_code, inclusion_percent, status, created_at, updated_at) " +
                    "VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
            ).use { ps ->
                ps.setLong(1, foodId)
                ps.setString(2, code)
                ps.setInt(3, percent)
                ps.executeUpdate()
            }
        }
        return foodId
    }

    private fun ensureSubstance(connection: Connection, code: String) {
        val exists = connection.prepareStatement("SELECT 1 FROM avoidance_substance WHERE code = ?").use { ps ->
            ps.setString(1, code)
            ps.executeQuery().use { it.next() }
        }
        if (exists) return
        connection.prepareStatement(
            "INSERT INTO avoidance_substance (code, korean_name, translations, status, created_at, updated_at) " +
                "VALUES (?, ?, '{}', 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
        ).use { ps ->
            ps.setString(1, code)
            ps.setString(2, "시나리오성분-$code")
            ps.executeUpdate()
        }
    }
}
