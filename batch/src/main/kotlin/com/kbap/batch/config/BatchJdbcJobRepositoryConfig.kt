package com.kbap.batch.config

import org.springframework.batch.core.configuration.support.DefaultBatchConfiguration
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.core.task.TaskExecutor
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
class BatchJdbcJobRepositoryConfig(
    private val batchDataSource: DataSource,
    private val batchTransactionManager: PlatformTransactionManager,
) : DefaultBatchConfiguration() {
    @Bean
    override fun jobRepository(): JobRepository {
        val factory = JobRepositoryFactoryBean()
        factory.setDataSource(batchDataSource)
        factory.setTransactionManager(batchTransactionManager)
        factory.afterPropertiesSet()
        return factory.`object`
    }

    override fun getTransactionManager(): PlatformTransactionManager = batchTransactionManager

    override fun getTaskExecutor(): TaskExecutor = SimpleAsyncTaskExecutor("batch-job-")
}
