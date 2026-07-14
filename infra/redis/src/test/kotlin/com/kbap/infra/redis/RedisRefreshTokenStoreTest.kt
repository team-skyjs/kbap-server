package com.kbap.infra.redis

import com.kbap.core.testsupport.RedisContainerConfig
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

    given("refresh 세션 원자적 소비 — rotation 경합 방어") {
        `when`("저장된 jti 를 consume 하면") {
            then("회원 식별자를 돌려주면서 키를 함께 제거한다(한 연산)") {
                store.save("jti-consume", memberId = 30L, ttl = ttl)

                store.consume("jti-consume") shouldBe 30L
                store.consume("jti-consume").shouldBeNull()
            }
        }

        `when`("없는 jti 를 consume 하면") {
            then("null 을 반환한다") {
                store.consume("jti-consume-absent").shouldBeNull()
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

                store.consume("jti-device-1") shouldBe 20L
                store.consume("jti-device-2") shouldBe 20L
            }
        }
    }
})
