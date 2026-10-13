package com.kbap.api.home

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.LocalDateTime

class HomeMarketingPopularPropertiesTest : BehaviorSpec({
    val startsAt = LocalDateTime.of(2026, 11, 1, 0, 0)
    val endsAt = LocalDateTime.of(2026, 11, 15, 0, 0)
    val inside = LocalDateTime.of(2026, 11, 3, 12, 0)

    fun properties(
        starts: LocalDateTime? = startsAt,
        ends: LocalDateTime? = endsAt,
        ids: List<Long> = listOf(6L, 4L, 5L),
    ) = HomeMarketingPopularProperties(startsAt = starts, endsAt = ends, foodIds = ids)

    given("마케팅 인기 음식 설정") {
        `when`("기간 안에 조회하면") {
            then("설정한 id 를 설정 순서대로 돌려준다") {
                properties().activeFoodIds(inside) shouldContainExactly listOf(6L, 4L, 5L)
            }
        }

        `when`("같은 id 가 두 번 있으면") {
            then("첫 등장만 남긴다") {
                properties(ids = listOf(6L, 4L, 6L, 5L)).activeFoodIds(inside) shouldContainExactly listOf(6L, 4L, 5L)
            }
        }

        `when`("시작 전이면") {
            then("빈 목록이다") {
                properties().activeFoodIds(startsAt.minusSeconds(1)).shouldBeEmpty()
            }
        }

        `when`("종료 이후면") {
            then("빈 목록이다") {
                properties().activeFoodIds(endsAt.plusSeconds(1)).shouldBeEmpty()
            }
        }

        `when`("시작 시각 정각이면") {
            then("활성이다") {
                properties().activeFoodIds(startsAt) shouldContainExactly listOf(6L, 4L, 5L)
            }
        }

        `when`("종료 시각 정각이면") {
            then("빈 목록이다") {
                properties().activeFoodIds(endsAt).shouldBeEmpty()
            }
        }

        `when`("시작·종료 중 하나가 null 이면") {
            then("빈 목록이다") {
                properties(starts = null).activeFoodIds(inside).shouldBeEmpty()
                properties(ends = null).activeFoodIds(inside).shouldBeEmpty()
            }
        }

        `when`("시작이 종료보다 늦으면") {
            then("빈 목록이다") {
                properties(starts = endsAt, ends = startsAt).activeFoodIds(inside).shouldBeEmpty()
            }
        }

        `when`("기간 안이지만 food-ids 가 비어 있으면") {
            then("빈 목록이다") {
                properties(ids = emptyList()).activeFoodIds(inside).shouldBeEmpty()
            }
        }
    }

    given("env 플레이스홀더 바인딩") {
        fun bind(startsAt: String, endsAt: String, foodIds: String): HomeMarketingPopularProperties =
            Binder(
                MapConfigurationPropertySource(
                    mapOf(
                        "kbap.home.marketing-popular.starts-at" to startsAt,
                        "kbap.home.marketing-popular.ends-at" to endsAt,
                        "kbap.home.marketing-popular.food-ids" to foodIds,
                    ),
                ),
            ).bind("kbap.home.marketing-popular", HomeMarketingPopularProperties::class.java).get()

        `when`("세 키가 전부 빈 문자열이면") {
            then("시작·종료는 null, 목록은 빈 목록으로 바인딩된다") {
                val bound = bind("", "", "")

                bound.startsAt.shouldBeNull()
                bound.endsAt.shouldBeNull()
                bound.foodIds.shouldBeEmpty()
            }
        }

        `when`("콤마 목록과 ISO 시각이면") {
            then("List<Long> 과 LocalDateTime 으로 바인딩된다") {
                val bound = bind("2026-11-01T00:00:00", "2026-11-15T00:00:00", "6,4,5")

                bound.startsAt shouldBe startsAt
                bound.endsAt shouldBe endsAt
                bound.foodIds shouldContainExactly listOf(6L, 4L, 5L)
            }
        }
    }
})
