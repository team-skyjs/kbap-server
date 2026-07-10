package com.meogo.infra.persistence.testsupport

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class RedisContainerConfig {

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(REDIS_PORT)

    companion object {
        const val REDIS_IMAGE: String = "redis:8"
        const val REDIS_PORT: Int = 6379
    }
}
