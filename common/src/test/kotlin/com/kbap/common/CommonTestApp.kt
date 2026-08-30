package com.kbap.common

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

@SpringBootConfiguration
@EnableAutoConfiguration
@AutoConfigurationPackage(basePackages = ["com.kbap.common.domain"])
class CommonTestApp
