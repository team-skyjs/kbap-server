package com.kbap.api.ingredient

import javax.sql.DataSource

object IngredientTestSeed {
    private const val CATALOG_SEED_RESOURCE = "db/migration/V2026.07.16.21.38.42__seed_avoidance_catalog.sql"
    private const val IMAGE_PATH_RESOURCE = "db/migration/V2026.08.11.15.35.50__ingredient_image_path.sql"

    fun restoreCatalog(dataSource: DataSource) {
        val statements = statementsOf(CATALOG_SEED_RESOURCE)
            .map { it.replace("INSERT INTO avoidance_substance ", "INSERT INTO ingredients ") } +
            statementsOf(IMAGE_PATH_RESOURCE).filterNot { it.startsWith("ALTER ", ignoreCase = true) }

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DELETE FROM ingredients")
                statements.forEach { statement.execute(it) }
            }
        }
    }

    private fun statementsOf(resourcePath: String): List<String> =
        Thread.currentThread().contextClassLoader.getResource(resourcePath)!!.readText()
            .lineSequence()
            .filterNot { it.trim().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
