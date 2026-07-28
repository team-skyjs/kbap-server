package com.kbap.domain.bookmark

import com.kbap.common.domain.member.SocialAccountDeleter
import com.kbap.common.domain.member.model.SocialProvider
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType

// BookmarkService 서비스 수준 통합 테스트용 부트 구성 — bookmark 가 의존하는 food/member/avoidance
// 서비스 그래프까지 올리고, 외부 seam(SocialAccountDeleter)은 no-op 으로 대체한다.
@SpringBootConfiguration
@EnableAutoConfiguration
@AutoConfigurationPackage(basePackages = ["com.kbap.domain", "com.kbap.common.domain"])
@ComponentScan(
    basePackages = [
        "com.kbap.domain.bookmark",
        "com.kbap.common.domain.food",
        "com.kbap.common.domain.member",
        "com.kbap.common.domain.avoidance",
    ],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.ANNOTATION, classes = [SpringBootConfiguration::class]),
    ],
)
class BookmarkServiceTestApp {
    @Bean
    fun socialAccountDeleter(): SocialAccountDeleter =
        object : SocialAccountDeleter {
            override fun delete(provider: SocialProvider, providerUserId: String) = Unit
        }
}
