package com.kbap.api.translation

import com.kbap.common.domain.LanguageCode
import com.kbap.common.infra.llm.config.LlmConfiguration
import com.kbap.common.infra.llm.config.LlmModelProperties
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.FileSystemResource
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DeployedTranslationEngineSettingsTest : BehaviorSpec({

    fun deployedTranslationProps(profile: String? = null): LlmModelProperties.VisionProps {
        val environment = StandardEnvironment()
        environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        listOfNotNull(profile?.let { "application-$it.yml" }, "application.yml").forEach { file ->
            YamlPropertySourceLoader().load(file, FileSystemResource("src/main/resources/$file")).forEach(environment.propertySources::addLast)
        }
        return Binder.get(environment).bind("kbap.llm.translation", LlmModelProperties.VisionProps::class.java).get()
    }

    given("배포 설정 파일(application.yml)의 번역 엔진 설정") {
        `when`("그 값으로 번역기를 조립해 5xx·429 로 답하는 엔진을 부르면") {
            then("다시 보내지 않는다 — 배포 설정의 재시도 수가 0 이라 제한 시간을 한 번만 쓴다") {
                listOf(500, 503, 429).forEach { status ->
                    val requests = AtomicInteger()
                    val handlers = Executors.newCachedThreadPool()
                    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                    server.executor = handlers
                    server.createContext("/") { exchange ->
                        exchange.requestBody.readBytes()
                        requests.incrementAndGet()
                        val body = """{"error":{"message":"x","type":"server_error"}}""".toByteArray()
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(status, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                    server.start()
                    try {
                        val props = deployedTranslationProps().copy(apiKey = "test-key", baseUrl = "http://127.0.0.1:${server.address.port}/v1")
                        val translator = LlmConfiguration().textTranslator(LlmModelProperties(translation = props), ApplicationEventPublisher { })

                        runCatching { translator.translate("hello there", LanguageCode.KO) }.isFailure shouldBe true
                        requests.get() shouldBe 1
                    } finally {
                        server.stop(0)
                        handlers.shutdownNow()
                    }
                }
            }
        }

        `when`("배포 프로필(dev·staging·prod) 파일을 그 위에 얹으면") {
            then("재시도 0·제한 12초가 그대로다 — 프로필 파일이 이 값을 바꾸지 않는다") {
                listOf("dev", "staging", "prod").forEach { profile ->
                    withClue(profile) {
                        val props = deployedTranslationProps(profile)

                        props.maxRetries shouldBe 0
                        props.timeout shouldBe Duration.ofSeconds(12)
                    }
                }
            }
        }

        `when`("환경변수로 바꾸지 않으면") {
            then("엔진 호출 제한은 12초다 — 앱의 요청 타임아웃(15초)보다 짧다") {
                deployedTranslationProps().timeout shouldBe Duration.ofSeconds(12)
            }
        }
    }
})
