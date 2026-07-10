package com.meogo.infra.persistence.auth

import com.meogo.infra.persistence.testsupport.RedisContainerConfig
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

class RefreshTokenRedisAdapterTest : BehaviorSpec({

    val container = GenericContainer(DockerImageName.parse(RedisContainerConfig.REDIS_IMAGE))
        .withExposedPorts(RedisContainerConfig.REDIS_PORT)

    lateinit var redisTemplate: StringRedisTemplate
    lateinit var adapter: RefreshTokenRedisAdapter

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
        adapter = RefreshTokenRedisAdapter(redisTemplate)
    }

    afterSpec {
        container.stop()
    }

    val ttl = Duration.ofDays(14)

    given("refresh 세션 저장") {
        `when`("jti 로 저장하면") {
            then("같은 jti 로 회원 식별자를 복원한다") {
                adapter.save("jti-save", memberId = 11L, ttl = ttl)

                adapter.findMemberId("jti-save") shouldBe 11L
            }
        }

        `when`("저장 직후 만료 시간을 확인하면") {
            then("지정한 수명으로 TTL 이 설정된다") {
                adapter.save("jti-ttl", memberId = 12L, ttl = Duration.ofMinutes(10))

                val remaining = redisTemplate.getExpire("auth:refresh:jti-ttl", TimeUnit.SECONDS)
                (remaining in 1..600) shouldBe true
            }
        }
    }

    given("저장되지 않은 jti") {
        `when`("조회하면") {
            then("null 을 반환한다") {
                adapter.findMemberId("jti-absent").shouldBeNull()
            }
        }
    }

    given("refresh 세션 폐기") {
        `when`("저장된 jti 를 삭제하면") {
            then("이후 조회는 null 이다") {
                adapter.save("jti-delete", memberId = 13L, ttl = ttl)

                adapter.delete("jti-delete")

                adapter.findMemberId("jti-delete").shouldBeNull()
            }
        }

        `when`("없는 jti 를 삭제하면") {
            then("예외 없이 통과한다") {
                adapter.delete("jti-never-existed")
            }
        }
    }

    given("회원별 다중 기기 세션") {
        `when`("같은 회원이 서로 다른 jti 로 두 번 저장하면") {
            then("두 세션이 함께 유지된다") {
                adapter.save("jti-device-1", memberId = 20L, ttl = ttl)
                adapter.save("jti-device-2", memberId = 20L, ttl = ttl)

                adapter.findMemberId("jti-device-1") shouldBe 20L
                adapter.findMemberId("jti-device-2") shouldBe 20L
            }
        }
    }
})
