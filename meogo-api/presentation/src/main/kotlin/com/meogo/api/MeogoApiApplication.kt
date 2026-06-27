package com.meogo.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.meogo.api"])
class MeogoApiApplication

fun main(args: Array<String>) {
	runApplication<MeogoApiApplication>(*args)
}
