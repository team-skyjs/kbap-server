package com.kbap.batch

import com.kbap.batch.notification.FakePushReceiptClientConfig
import com.kbap.batch.notification.FakePushClientConfig
import com.kbap.batch.notification.MutableClockConfig
import com.kbap.batch.trigger.rest.SlowJobTestConfig
import com.kbap.common.core.testsupport.MySqlContainerConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class, SlowJobTestConfig::class, FakePushClientConfig::class, FakePushReceiptClientConfig::class, MutableClockConfig::class)
annotation class BatchIntegrationTest
