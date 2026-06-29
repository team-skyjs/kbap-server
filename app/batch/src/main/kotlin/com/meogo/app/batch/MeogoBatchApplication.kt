package com.meogo.app.batch

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.meogo"])
class MeogoBatchApplication

fun main(args: Array<String>) {
    runApplication<MeogoBatchApplication>(*args)
}
