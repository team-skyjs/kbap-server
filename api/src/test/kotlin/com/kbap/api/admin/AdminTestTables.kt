package com.kbap.api.admin

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

    fun execute(dataSource: DataSource, sql: String) {
        dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
    }
}
