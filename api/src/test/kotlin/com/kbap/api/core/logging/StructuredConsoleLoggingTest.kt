package com.kbap.api.core.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.logging.logback.StructuredLogEncoder
import org.springframework.core.env.Environment
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource

class StructuredConsoleLoggingTest : BehaviorSpec({
    given("staging·prod 의 JSON 구조화 로그 설정") {
        `when`("운영 프로필 설정을 읽으면") {
            then("콘솔 로그 형식이 ecs 다") {
                listOf("staging", "prod").forEach { profile ->
                    val properties = YamlPropertiesFactoryBean()
                        .apply { setResources(ClassPathResource("application-$profile.yml")) }
                        .`object`!!
                    properties.getProperty("logging.structured.format.console") shouldBe "ecs"
                }
            }
        }

        `when`("ecs 인코더로 이벤트를 찍으면") {
            then("상관 키·회원 식별자가 JSON 필드로 나간다") {
                val loggerContext = LoggerContext().apply {
                    putObject(Environment::class.java.name, StandardEnvironment())
                }
                val encoder = StructuredLogEncoder().apply {
                    context = loggerContext
                    setFormat("ecs")
                    start()
                }
                val event = LoggingEvent(
                    Logger::class.java.name,
                    loggerContext.getLogger(Logger.ROOT_LOGGER_NAME),
                    Level.INFO,
                    "hello",
                    null,
                    null,
                ).apply {
                    mdcPropertyMap = mapOf(
                        RequestLoggingFilter.REQUEST_ID_KEY to "test-request-id",
                        RequestLoggingFilter.MEMBER_ID_KEY to "42",
                    )
                }

                val json = String(encoder.encode(event), Charsets.UTF_8)

                json shouldContain "\"requestId\":\"test-request-id\""
                json shouldContain "\"memberId\":\"42\""
                json.startsWith("{") shouldBe true
            }
        }
    }
})
