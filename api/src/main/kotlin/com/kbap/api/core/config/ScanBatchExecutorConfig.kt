package com.kbap.api.core.config

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskDecorator
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
class ScanBatchExecutorConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean(name = [SCAN_BATCH_EXECUTOR])
    fun scanBatchExecutor(jdbcTemplate: JdbcTemplate, taskDecorator: TaskDecorator): ThreadPoolTaskExecutor {
        val limit = runCatching {
            jdbcTemplate.queryForObject("SELECT global_call_limit FROM scan_runtime_control WHERE singleton_key = 1", Int::class.java)
        }.onFailure { log.warn("scan_runtime_control 를 읽지 못해 묶음 스캔 동시 호출 상한 기본값 {} 사용", DEFAULT_CALL_LIMIT, it) }
            .getOrNull() ?: DEFAULT_CALL_LIMIT
        return ThreadPoolTaskExecutor().apply {
            corePoolSize = limit
            maxPoolSize = limit
            queueCapacity = QUEUE_CAPACITY
            setThreadNamePrefix("scan-batch-")
            setTaskDecorator(taskDecorator)
            initialize()
        }
    }

    companion object {
        const val SCAN_BATCH_EXECUTOR = "scanBatchExecutor"
        private const val DEFAULT_CALL_LIMIT = 4
        private const val QUEUE_CAPACITY = 200
    }
}
