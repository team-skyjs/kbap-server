package com.kbap.common.core.testsupport

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class MySqlContainerConfig {

    @Bean
    @ServiceConnection
    fun mySqlContainer(): MySQLContainer =
        MySQLContainer(DockerImageName.parse(MYSQL_IMAGE))
            .withDatabaseName(DATABASE_NAME)
            .withUsername(USERNAME)
            .withPassword(PASSWORD)
            .withCommand(
                "--character-set-server=utf8mb4",
                "--collation-server=utf8mb4_unicode_ci",
                "--default-time-zone=+09:00",
            )

    companion object {
        const val MYSQL_IMAGE: String = "mysql:8.4"
        const val DATABASE_NAME: String = "kbap"
        const val USERNAME: String = "kbap"
        const val PASSWORD: String = "kbap"
    }
}
