package com.kbap.app.api.config

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.yaml.snakeyaml.Yaml

class RedisSslConfigTest : BehaviorSpec({
    fun sslEnabledOf(profile: String): Any? {
        val resource = "application-$profile.yml"
        val stream = requireNotNull(RedisSslConfigTest::class.java.classLoader.getResourceAsStream(resource)) {
            "$resource 이(가) classpath 에 없다"
        }
        val root: Map<String, Any?> = stream.use { Yaml().load(it) }
        return listOf("spring", "data", "redis", "ssl", "enabled")
            .fold(root as Any?) { node, key -> (node as? Map<*, *>)?.get(key) }
    }

    given("환경 프로필 yml 의 Redis TLS 설정") {
        `when`("배포 프로필(dev·staging·prod)을 읽으면") {
            then("ssl.enabled 가 기본값 true 인 환경변수 주입으로 선언되어 있다") {
                listOf("dev", "staging", "prod").forEach { profile ->
                    withClue("application-$profile.yml") {
                        sslEnabledOf(profile) shouldBe "\${REDIS_SSL_ENABLED:true}"
                    }
                }
            }
        }
        `when`("local 프로필을 읽으면") {
            then("ssl.enabled 가 기본값 false 인 환경변수 주입으로 선언되어 있다") {
                sslEnabledOf("local") shouldBe "\${REDIS_SSL_ENABLED:false}"
            }
        }
    }
})
