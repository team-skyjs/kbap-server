package com.kbap.common.domain.block

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType

@SpringBootConfiguration
@EnableAutoConfiguration
@AutoConfigurationPackage(basePackages = ["com.kbap.common.domain"])
@ComponentScan(
    basePackages = ["com.kbap.common.domain.block", "com.kbap.common.domain.member"],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.ANNOTATION, classes = [SpringBootConfiguration::class]),
    ],
)
class BlockTestApp
