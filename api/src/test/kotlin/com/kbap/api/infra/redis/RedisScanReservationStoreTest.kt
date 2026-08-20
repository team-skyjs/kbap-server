package com.kbap.api.infra.redis

import com.kbap.common.core.testsupport.RedisContainerConfig
import com.kbap.common.port.scan.ScanReservationResult
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class RedisScanReservationStoreTest : BehaviorSpec({

    val container = GenericContainer(DockerImageName.parse(RedisContainerConfig.REDIS_IMAGE))
        .withExposedPorts(RedisContainerConfig.REDIS_PORT)

    lateinit var redisTemplate: StringRedisTemplate
    lateinit var store: RedisScanReservationStore

    beforeSpec {
        container.start()
        val connectionFactory = LettuceConnectionFactory(
            RedisStandaloneConfiguration(container.host, container.getMappedPort(RedisContainerConfig.REDIS_PORT)),
        )
        connectionFactory.afterPropertiesSet()
        redisTemplate = StringRedisTemplate(connectionFactory)
        redisTemplate.afterPropertiesSet()
        store = RedisScanReservationStore(redisTemplate, reservationTtlSeconds = 300)
    }

    afterSpec { container.stop() }

    beforeContainer {
        redisTemplate.connectionFactory!!.connection.serverCommands().flushAll()
    }

    given("reserve — 무료 슬롯 원자 예약") {
        `when`("확정 1회인 회원에게 서로 다른 요청 3개가 오면") {
            then("잔여 2슬롯만 예약되고 세 번째는 한도 초과다") {
                store.reserve(100L, "request-A", 1, 3) shouldBe ScanReservationResult.RESERVED
                store.reserve(100L, "request-B", 1, 3) shouldBe ScanReservationResult.RESERVED
                store.reserve(100L, "request-C", 1, 3) shouldBe ScanReservationResult.LIMIT_EXCEEDED
            }
        }
        `when`("잔여 1슬롯에 동시 요청 5개가 몰리면") {
            then("정확히 1개만 예약된다 — Lua 원자 연산") {
                val executor = Executors.newFixedThreadPool(5)
                val startGate = CountDownLatch(1)
                val results = (1..5).map { i ->
                    executor.submit<ScanReservationResult> {
                        startGate.await()
                        store.reserve(101L, "request-$i", 2, 3)
                    }
                }
                startGate.countDown()
                results.map { it.get() }.count { it == ScanReservationResult.RESERVED } shouldBe 1
                executor.shutdown()
            }
        }
        `when`("같은 requestId 가 다시 들어오면") {
            then("새 슬롯으로 계산하지 않고 중복으로 판정한다") {
                store.reserve(102L, "retry-request", 0, 3) shouldBe ScanReservationResult.RESERVED
                store.reserve(102L, "retry-request", 0, 3) shouldBe ScanReservationResult.DUPLICATE_REQUEST
            }
        }
        `when`("해금 판정 없이 확정 횟수가 이미 한도 이상이면") {
            then("예약이 거절된다") {
                store.reserve(103L, "request-A", 3, 3) shouldBe ScanReservationResult.LIMIT_EXCEEDED
            }
        }
    }

    given("release — 예약 해제") {
        `when`("예약을 해제하면") {
            then("슬롯이 즉시 복구되고, 중복 해제도 무해하다(멱등)") {
                store.reserve(200L, "request-A", 2, 3) shouldBe ScanReservationResult.RESERVED
                store.reserve(200L, "request-B", 2, 3) shouldBe ScanReservationResult.LIMIT_EXCEEDED

                store.release(200L, "request-A")
                store.release(200L, "request-A")
                store.reserve(200L, "request-B", 2, 3) shouldBe ScanReservationResult.RESERVED
            }
        }
    }

    given("만료 — 프로세스 장애 자생 복구") {
        `when`("TTL 이 지난 예약이 남아 있으면") {
            then("다음 예약 시도에서 정리되고 슬롯이 복구된다") {
                val shortTtlStore = RedisScanReservationStore(redisTemplate, reservationTtlSeconds = 0)
                shortTtlStore.reserve(300L, "crashed-request", 2, 3) shouldBe ScanReservationResult.RESERVED

                Thread.sleep(50)
                store.reserve(300L, "next-request", 2, 3) shouldBe ScanReservationResult.RESERVED
            }
        }
    }
})
