package com.kbap.api

import javax.sql.DataSource

object TestTables {
    private val tables = listOf(
        "review_like",
        "member_ranking_event",
        "food_review",
        "bookmark",
        "scan_history",
        "image_batch_item",
        "image_batch",
        "food_content_outbox",
        "food_vector_outbox",
        "food_image",
        "food",
        "community_comment",
        "community_post",
        "order_item",
        "orders",
        "report",
        "uploaded_image",
        "member_block",
        "member",
        "llm_call_cost",
    )

    fun clearAll(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET FOREIGN_KEY_CHECKS = 0")
                tables.forEach { statement.execute("DELETE FROM $it") }
                statement.execute("SET FOREIGN_KEY_CHECKS = 1")
            }
        }
    }
}
