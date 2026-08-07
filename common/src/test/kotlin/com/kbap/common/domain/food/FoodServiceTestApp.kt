package com.kbap.common.domain.food

import com.kbap.common.port.auth.SocialAccountDeleter
import com.kbap.common.domain.member.model.SocialProvider
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType

// FoodService 서비스 수준 통합 테스트용 부트 구성 — food 가 의존하는 member/avoidance
// 서비스 그래프까지 올리고, 외부 seam(SocialAccountDeleter)은 no-op 으로 대체한다.
@SpringBootConfiguration
@EnableAutoConfiguration
@AutoConfigurationPackage(basePackages = ["com.kbap.common.domain"])
@ComponentScan(
    basePackages = ["com.kbap.common.domain.food", "com.kbap.common.domain.member", "com.kbap.common.domain.ingredient"],
    excludeFilters = [
        // 같은 패키지의 다른 테스트 부트 구성이 스캔에 섞여 빈이 중복 등록되는 것 방지
        ComponentScan.Filter(type = FilterType.ANNOTATION, classes = [SpringBootConfiguration::class]),
    ],
)
class FoodServiceTestApp {
    @Bean
    fun socialAccountDeleter(): SocialAccountDeleter =
        object : SocialAccountDeleter {
            override fun delete(provider: SocialProvider, providerUserId: String) = Unit
        }
}
