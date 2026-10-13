package com.kbap.api.home

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

class HomeMarketingPopularPropertiesTest : BehaviorSpec({
    fun bind(foodIds: String): HomeMarketingPopularProperties =
        Binder(MapConfigurationPropertySource(mapOf("kbap.home.marketing-popular.food-ids" to foodIds)))
            .bind("kbap.home.marketing-popular", HomeMarketingPopularProperties::class.java)
            .get()

    given("env 플레이스홀더 바인딩") {
        `when`("food-ids 가 빈 문자열이면") {
            then("빈 목록으로 바인딩된다") {
                bind("").foodIds.shouldBeEmpty()
            }
        }

        `when`("콤마 목록이면") {
            then("List<Long> 으로 순서대로 바인딩된다") {
                bind("6,4,5").foodIds shouldContainExactly listOf(6L, 4L, 5L)
            }
        }
    }
})
