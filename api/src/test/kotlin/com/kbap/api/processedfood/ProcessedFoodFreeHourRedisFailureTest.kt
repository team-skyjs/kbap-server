package com.kbap.api.processedfood

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate

class ProcessedFoodFreeHourRedisFailureTest : BehaviorSpec({
    given("가공식품 무료 창(KB-876)") {
        `when`("Redis 가 실패하면") {
            then("조회는 창 없음(null)이라 호출부가 지금처럼 차감하고, 차감 뒤 창 열기도 null 이다") {
                val redis = mock(StringRedisTemplate::class.java)
                `when`(redis.opsForValue()).thenThrow(RedisConnectionFailureException("down"))
                val freeHour = ProcessedFoodFreeHour(redis)

                freeHour.open(1L) shouldBe null
                freeHour.freeUntil(1L) shouldBe null
            }
        }
    }
})
