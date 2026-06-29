package com.meogo.batch

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.meogo"])
class MeogoBatchApplication

fun main(args: Array<String>) {
    runApplication<MeogoBatchApplication>(*args)
}
