package com.kbap

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KbapApiApplication

fun main(args: Array<String>) {
	runApplication<KbapApiApplication>(*args)
}
