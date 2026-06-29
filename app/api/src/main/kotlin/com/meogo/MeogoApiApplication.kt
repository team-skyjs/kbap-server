package com.meogo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MeogoApiApplication

fun main(args: Array<String>) {
	runApplication<MeogoApiApplication>(*args)
}
