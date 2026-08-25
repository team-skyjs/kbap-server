package com.kbap.api.admin

import java.time.LocalDateTime
import javax.sql.DataSource

object AdminTestTables {
    fun clear(dataSource: DataSource, vararg tables: String) {
        dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.execute("SET FOREIGN_KEY_CHECKS = 0")
                tables.forEach {
                    st.execute("DELETE FROM $it")
                    st.execute("ALTER TABLE $it AUTO_INCREMENT = 1")
                }
                st.execute("SET FOREIGN_KEY_CHECKS = 1")
            }
        }
    }

    fun ageSentAt(dataSource: DataSource, outboxId: Long, hours: Long) {
        dataSource.connection.use { c ->
            c.prepareStatement("UPDATE food_content_outbox SET sent_at = ? WHERE id = ?").use { ps ->
                ps.setObject(1, LocalDateTime.now().minusHours(hours))
                ps.setLong(2, outboxId)
                ps.executeUpdate()
            }
        }
    }

    fun execute(dataSource: DataSource, sql: String) {
        dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
    }
}
