package com.kbap.api.infra.redis

import com.kbap.common.core.testsupport.RedisContainerConfig
import com.kbap.common.port.auth.IssuedRefreshToken
import com.kbap.common.port.auth.RefreshRotation
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.concurrent.TimeUnit

class RedisRefreshTokenStoreTest : BehaviorSpec({

    val container = GenericContainer(DockerImageName.parse(RedisContainerConfig.REDIS_IMAGE))
        .withExposedPorts(RedisContainerConfig.REDIS_PORT)

    lateinit var redisTemplate: StringRedisTemplate
    lateinit var store: RedisRefreshTokenStore

    beforeSpec {
        container.start()
        val connectionFactory = LettuceConnectionFactory(
            RedisStandaloneConfiguration(
                container.host,
                container.getMappedPort(RedisContainerConfig.REDIS_PORT),
            ),
        )
        connectionFactory.afterPropertiesSet()
        redisTemplate = StringRedisTemplate(connectionFactory)
        store = RedisRefreshTokenStore(redisTemplate)
    }

    afterSpec {
        container.stop()
    }

    val ttl = Duration.ofDays(14)

    given("refresh 세션 저장") {
        `when`("jti 로 저장하면") {
            then("같은 jti 로 회원 식별자를 복원한다") {
                store.save("jti-save", memberId = 11L, ttl = ttl)

                redisTemplate.opsForValue().get("auth:refresh:jti-save") shouldBe "11"
            }
        }

        `when`("저장 직후 만료 시간을 확인하면") {
            then("지정한 수명으로 TTL 이 설정된다") {
                store.save("jti-ttl", memberId = 12L, ttl = Duration.ofMinutes(10))

                val remaining = redisTemplate.getExpire("auth:refresh:jti-ttl", TimeUnit.SECONDS)
                (remaining in 1..600) shouldBe true
            }
        }
    }

    val grace = Duration.ofSeconds(60)

    given("refresh 회전 — 직전 토큰 유예 창") {
        `when`("저장된 jti 를 회전하면") {
            then("회원과 후속 토큰을 돌려주고, 후속 세션을 저장하며, 직전 키는 유예 창 길이의 '회전됨' 표시로 바뀐다") {
                store.save("jti-rotate", memberId = 30L, ttl = ttl)

                val rotation = store.rotate("jti-rotate", IssuedRefreshToken("token-next", "jti-next"), ttl, grace)

                rotation shouldBe RefreshRotation(memberId = 30L, refreshToken = "token-next")
                redisTemplate.opsForValue().get("auth:refresh:jti-next") shouldBe "30"
                (redisTemplate.getExpire("auth:refresh:jti-rotate", TimeUnit.SECONDS) in 1..60) shouldBe true
            }
        }

        `when`("유예 창 안에 같은 jti 를 다시 회전하면") {
            then("새 세션을 만들지 않고 처음 후속 토큰을 그대로 돌려준다") {
                store.save("jti-replay", memberId = 31L, ttl = ttl)
                store.rotate("jti-replay", IssuedRefreshToken("token-first", "jti-first"), ttl, grace)

                val replay = store.rotate("jti-replay", IssuedRefreshToken("token-second", "jti-second"), ttl, grace)

                replay shouldBe RefreshRotation(memberId = 31L, refreshToken = "token-first")
                redisTemplate.hasKey("auth:refresh:jti-second") shouldBe false
            }
        }

        `when`("유예 창이 지난 뒤 같은 jti 를 다시 회전하면") {
            then("null — 직전 토큰은 더는 통하지 않는다") {
                store.save("jti-expired", memberId = 32L, ttl = ttl)
                store.rotate("jti-expired", IssuedRefreshToken("token-a", "jti-a"), ttl, Duration.ofSeconds(1))
                Thread.sleep(1_500)

                store.rotate("jti-expired", IssuedRefreshToken("token-b", "jti-b"), ttl, Duration.ofSeconds(1)).shouldBeNull()
                redisTemplate.hasKey("auth:refresh:jti-b") shouldBe false
            }
        }

        `when`("유예 창 안이지만 후속 세션이 로그아웃으로 폐기됐으면") {
            then("null — 폐기된 후속 토큰을 되살려 내주지 않는다") {
                store.save("jti-logged-out", memberId = 33L, ttl = ttl)
                store.rotate("jti-logged-out", IssuedRefreshToken("token-c", "jti-c"), ttl, grace)
                store.delete("jti-c")

                store.rotate("jti-logged-out", IssuedRefreshToken("token-d", "jti-d"), ttl, grace).shouldBeNull()
            }
        }

        `when`("없는 jti 를 회전하면") {
            then("null 을 반환하고 아무것도 저장하지 않는다") {
                store.rotate("jti-absent", IssuedRefreshToken("token-e", "jti-e"), ttl, grace).shouldBeNull()
                redisTemplate.hasKey("auth:refresh:jti-e") shouldBe false
            }
        }

        `when`("같은 jti 로 회전이 동시에 여러 번 오면") {
            then("모두 같은 후속 토큰을 받고, 유효한 후속 세션은 하나뿐이다") {
                store.save("jti-race", memberId = 34L, ttl = ttl)
                val start = java.util.concurrent.CountDownLatch(1)
                val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
                val results = try {
                    val futures = (1..8).map { i ->
                        pool.submit(java.util.concurrent.Callable {
                            start.await()
                            store.rotate("jti-race", IssuedRefreshToken("token-race-$i", "jti-race-$i"), ttl, grace)
                        })
                    }
                    start.countDown()
                    futures.map { it.get(10, TimeUnit.SECONDS) }
                } finally {
                    pool.shutdownNow()
                }

                results.map { it?.refreshToken }.toSet().size shouldBe 1
                results.all { it?.memberId == 34L } shouldBe true
                (1..8).count { redisTemplate.hasKey("auth:refresh:jti-race-$it") } shouldBe 1
            }
        }
    }

    given("refresh 세션 폐기") {
        `when`("저장된 jti 를 삭제하면") {
            then("이후 조회는 null 이다") {
                store.save("jti-delete", memberId = 13L, ttl = ttl)

                store.delete("jti-delete")

                redisTemplate.opsForValue().get("auth:refresh:jti-delete").shouldBeNull()
            }
        }

        `when`("없는 jti 를 삭제하면") {
            then("예외 없이 통과한다") {
                store.delete("jti-never-existed")
            }
        }
    }

    given("회원별 다중 기기 세션") {
        `when`("같은 회원이 서로 다른 jti 로 두 번 저장하면") {
            then("두 세션이 함께 유지된다") {
                store.save("jti-device-1", memberId = 20L, ttl = ttl)
                store.save("jti-device-2", memberId = 20L, ttl = ttl)

                redisTemplate.opsForValue().get("auth:refresh:jti-device-1") shouldBe "20"
                redisTemplate.opsForValue().get("auth:refresh:jti-device-2") shouldBe "20"
            }
        }
    }
})
