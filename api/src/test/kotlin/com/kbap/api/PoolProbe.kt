package com.kbap.api

import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

object PoolProbe {
    fun leastActiveConnections(dataSource: DataSource): Int {
        val pool = dataSource.unwrap(HikariDataSource::class.java).hikariPoolMXBean
        return (1..20).minOf {
            Thread.sleep(10)
            pool.activeConnections
        }
    }
}
