package com.kbap.common.domain.review

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// 전체 피드 쿼리가 Food 존재 서브쿼리를 쓰므로 food 엔티티·레포까지 스캔한다
@SpringBootConfiguration
@EnableAutoConfiguration
@AutoConfigurationPackage(basePackages = ["com.kbap.common.domain"])
class ReviewTestApp
