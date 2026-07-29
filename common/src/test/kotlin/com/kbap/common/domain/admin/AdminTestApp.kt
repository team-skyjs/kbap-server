package com.kbap.common.domain.admin

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
    excludeFilters = [
        // 같은 패키지의 다른 테스트 부트 구성이 스캔에 섞여 빈이 중복 등록되는 것 방지
        ComponentScan.Filter(type = FilterType.ANNOTATION, classes = [SpringBootConfiguration::class]),
    ],
)
class AdminTestApp
