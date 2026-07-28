package com.kbap.common.domain.food

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType

// 레포지토리/엔티티 수준 테스트 전용 부트 구성.
// FoodService 는 타 도메인 서비스·외부 seam 을 주입받으므로 이 모듈 단독 컨텍스트에서는 스캔에서 제외한다
// (서비스 레벨 검증은 api 통합 테스트가 담당).
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [FoodService::class]),
        // 같은 패키지의 다른 테스트 부트 구성이 스캔에 섞여 빈이 중복 등록되는 것 방지
        ComponentScan.Filter(type = FilterType.ANNOTATION, classes = [SpringBootConfiguration::class]),
    ],
)
class FoodTestApp
