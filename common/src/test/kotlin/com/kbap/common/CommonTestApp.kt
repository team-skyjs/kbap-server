package com.kbap.common

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.ComponentScan

@SpringBootConfiguration
@EnableAutoConfiguration
@AutoConfigurationPackage(basePackages = ["com.kbap.common.domain"])
@ComponentScan(basePackages = ["com.kbap.common.domain"])
class CommonTestApp
