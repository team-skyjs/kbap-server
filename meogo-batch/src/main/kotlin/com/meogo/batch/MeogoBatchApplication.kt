package com.meogo.batch

import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.meogo"])
@AutoConfigurationPackage(basePackages = ["com.meogo"])
class MeogoBatchApplication

fun main(args: Array<String>) {
    runApplication<MeogoBatchApplication>(*args)
}
