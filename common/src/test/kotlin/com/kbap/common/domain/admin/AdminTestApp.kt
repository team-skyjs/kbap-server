package com.kbap.common.domain.admin

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.ANNOTATION, classes = [SpringBootConfiguration::class]),
    ],
)
class AdminTestApp
