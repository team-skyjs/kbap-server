package com.kbap.api.core.observability

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.FileSystemResource

class SentryConfigTest : BehaviorSpec({
    given("api application.yml 의 sentry 설정") {
        val properties = YamlPropertiesFactoryBean().apply {
            setResources(FileSystemResource("src/main/resources/application.yml"))
        }.`object`!!

        `when`("DSN 과 비활성 조건을 읽으면") {
            then("DSN 은 API_SENTRY_DSN 환경변수이고 없으면 빈 값이다") {
                properties.getProperty("sentry.dsn") shouldBe "\${API_SENTRY_DSN:}"
            }
        }

        `when`("예외 리졸버 순서를 읽으면") {
            then("전역 어드바이스보다 앞서도록 최우선 순위다") {
                properties.getProperty("sentry.exception-resolver-order") shouldBe Integer.MIN_VALUE.toString()
            }
        }

        `when`("개인정보·태그·릴리스 설정을 읽으면") {
            then("PII 를 보내지 않고 service=api, release 는 SENTRY_RELEASE 환경변수다") {
                properties.getProperty("sentry.send-default-pii") shouldBe "false"
                properties.getProperty("sentry.tags.service") shouldBe "api"
                properties.getProperty("sentry.release") shouldBe "\${SENTRY_RELEASE:}"
            }
        }

        `when`("로그 연동 수위를 읽으면") {
            then("ERROR 만 이벤트, INFO 이상은 breadcrumb 다") {
                properties.getProperty("sentry.logging.minimum-event-level") shouldBe "error"
                properties.getProperty("sentry.logging.minimum-breadcrumb-level") shouldBe "info"
            }
        }
    }
})
