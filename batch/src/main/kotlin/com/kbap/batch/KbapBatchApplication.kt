package com.kbap.batch

import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.kbap.batch", "com.kbap.common.infra.llm"])
@AutoConfigurationPackage(basePackages = ["com.kbap"])
class KbapBatchApplication

fun main(args: Array<String>) {
    runApplication<KbapBatchApplication>(*args)
}
