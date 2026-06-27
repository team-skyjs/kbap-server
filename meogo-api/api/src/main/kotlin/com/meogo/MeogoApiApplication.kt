package com.meogo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.meogo"])
class MeogoApiApplication

fun main(args: Array<String>) {
	runApplication<MeogoApiApplication>(*args)
}
