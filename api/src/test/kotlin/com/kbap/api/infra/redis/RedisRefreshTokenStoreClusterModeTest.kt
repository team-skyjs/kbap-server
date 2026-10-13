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
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RedisRefreshTokenStoreClusterModeTest : BehaviorSpec({

    val container = GenericContainer(DockerImageName.parse(RedisContainerConfig.REDIS_IMAGE))
        .withExposedPorts(RedisContainerConfig.REDIS_PORT)
        .withCommand("redis-server", "--cluster-enabled", "yes", "--cluster-config-file", "nodes.conf", "--appendonly", "no")
        .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))

    lateinit var redisTemplate: StringRedisTemplate
    lateinit var store: RedisRefreshTokenStore

    beforeSpec {
        container.start()
        container.execInContainer("redis-cli", "CLUSTER", "ADDSLOTSRANGE", "0", "16383")
        repeat(50) {
            if (container.execInContainer("redis-cli", "CLUSTER", "INFO").stdout.contains("cluster_state:ok")) return@repeat
            Thread.sleep(100)
        }
        val connectionFactory = LettuceConnectionFactory(
            RedisStandaloneConfiguration(container.host, container.getMappedPort(RedisContainerConfig.REDIS_PORT)),
        )
        connectionFactory.afterPropertiesSet()
        redisTemplate = StringRedisTemplate(connectionFactory)
        store = RedisRefreshTokenStore(redisTemplate)
    }

    afterSpec { container.stop() }

    val ttl = Duration.ofDays(14)
    val grace = Duration.ofSeconds(60)

    given("cluster mode Redis(prod·devstg 와 같은 단일 샤드 클러스터)") {
        `when`("픽스처가 정말 클러스터 모드인지 보면") {
            then("서로 다른 슬롯의 두 키를 한 명령에 쓰면 CROSSSLOT 으로 거절된다") {
                val crossSlot = runCatching { redisTemplate.opsForValue().multiGet(listOf("auth:refresh:a", "auth:refresh:b")) }.exceptionOrNull()
                (crossSlot?.let { generateSequence(it) { e -> e.cause }.any { e -> e.message?.contains("CROSSSLOT") == true } } ?: false) shouldBe true
            }
        }

        `when`("저장된 jti 를 회전하면") {
            then("회원과 후속 토큰을 돌려주고 후속 세션이 저장된다") {
                store.save("c-rotate", memberId = 40L, ttl = ttl)

                store.rotate("c-rotate", 40L, IssuedRefreshToken("c-token-next", "c-jti-next"), ttl, grace) shouldBe RefreshRotation(40L, "c-token-next")
                redisTemplate.opsForValue().get("auth:refresh:c-jti-next") shouldBe "40"
            }
        }

        `when`("유예 창 안에 같은 jti 를 다시 회전하면") {
            then("처음 후속 토큰을 그대로 돌려주고 두 번째 후속 세션은 남지 않는다") {
                store.save("c-replay", memberId = 41L, ttl = ttl)
                store.rotate("c-replay", 41L, IssuedRefreshToken("c-token-first", "c-jti-first"), ttl, grace)

                store.rotate("c-replay", 41L, IssuedRefreshToken("c-token-second", "c-jti-second"), ttl, grace) shouldBe RefreshRotation(41L, "c-token-first")
                redisTemplate.hasKey("auth:refresh:c-jti-second") shouldBe false
            }
        }

        `when`("후속 세션이 폐기된 뒤 유예 창 안에 다시 회전하면") {
            then("null") {
                store.save("c-revoked", memberId = 42L, ttl = ttl)
                store.rotate("c-revoked", 42L, IssuedRefreshToken("c-token-r", "c-jti-r"), ttl, grace)
                store.delete("c-jti-r")

                store.rotate("c-revoked", 42L, IssuedRefreshToken("c-token-r2", "c-jti-r2"), ttl, grace).shouldBeNull()
            }
        }

        `when`("회전 직후 직전 jti 를 삭제(옛 토큰 로그아웃)하면") {
            then("후속 세션도 지워진다") {
                store.save("c-logout", memberId = 43L, ttl = ttl)
                store.rotate("c-logout", 43L, IssuedRefreshToken("c-token-l", "c-jti-l"), ttl, grace)

                store.delete("c-logout")

                redisTemplate.hasKey("auth:refresh:c-logout") shouldBe false
                redisTemplate.hasKey("auth:refresh:c-jti-l") shouldBe false
            }
        }

        `when`("로그아웃(직전 토큰 키 삭제)이 회전보다 먼저 끝나면 — 경합 ②") {
            then("회전은 null(401) 이고 회전이 미리 써 둔 후속 키도 지워져 고아 세션이 남지 않는다") {
                store.save("c-logout-first", memberId = 45L, ttl = ttl)
                store.delete("c-logout-first")

                store.rotate("c-logout-first", 45L, IssuedRefreshToken("c-token-lf", "c-jti-lf"), ttl, grace).shouldBeNull()
                redisTemplate.hasKey("auth:refresh:c-jti-lf") shouldBe false
            }
        }

        `when`("저장된 회원과 다른 회원으로 회전을 요청하면") {
            then("null 이고 직전 세션은 그대로, 후속 키는 남지 않는다") {
                store.save("c-mismatch", memberId = 46L, ttl = ttl)

                store.rotate("c-mismatch", 47L, IssuedRefreshToken("c-token-mm", "c-jti-mm"), ttl, grace).shouldBeNull()
                redisTemplate.opsForValue().get("auth:refresh:c-mismatch") shouldBe "46"
                redisTemplate.hasKey("auth:refresh:c-jti-mm") shouldBe false
            }
        }

        `when`("같은 jti 로 회전이 동시에 8번 오면") {
            then("모두 같은 후속 토큰을 받고 유효한 후속 세션은 하나뿐이다") {
                store.save("c-race", memberId = 44L, ttl = ttl)
                val start = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(8)
                val results = try {
                    val futures = (1..8).map { i ->
                        pool.submit(Callable {
                            start.await()
                            store.rotate("c-race", 44L, IssuedRefreshToken("c-token-race-$i", "c-jti-race-$i"), ttl, grace)
                        })
                    }
                    start.countDown()
                    futures.map { it.get(10, TimeUnit.SECONDS) }
                } finally {
                    pool.shutdownNow()
                }

                results.map { it?.refreshToken }.toSet().size shouldBe 1
                (1..8).count { redisTemplate.hasKey("auth:refresh:c-jti-race-$it") } shouldBe 1
            }
        }
    }
})
