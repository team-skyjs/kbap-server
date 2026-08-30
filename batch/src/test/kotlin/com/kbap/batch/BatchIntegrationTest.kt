package com.kbap.batch

import com.kbap.batch.trigger.SlowJobTestConfig
import com.kbap.common.core.testsupport.MySqlContainerConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class, SlowJobTestConfig::class)
annotation class BatchIntegrationTest
