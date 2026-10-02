package com.kbap.api.core.config

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.FileSystemResource

class JpaConnectionConfigTest : BehaviorSpec({

    val runner = ApplicationContextRunner().withUserConfiguration(JpaConnectionConfig::class.java)

    given("트랜잭션이 끝나면 커넥션을 돌려주는 설정의 스위치") {
        `when`("스위치를 건드리지 않으면") {
            then("켜져 있다 — Hibernate 에 트랜잭션 단위 반환 방식을 건다") {
                runner.run { context ->
                    val properties = mutableMapOf<String, Any>()
                    context.getBean(HibernatePropertiesCustomizer::class.java).customize(properties)

                    properties["hibernate.connection.handling_mode"] shouldBe "DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION"
                }
            }
        }

        `when`("false 로 끄면") {
            then("설정을 걸지 않는다 — Hibernate 기본(요청이 끝날 때까지 커넥션을 쥠)으로 돌아간다") {
                runner.withPropertyValues("kbap.jpa.release-connection-after-transaction=false").run { context ->
                    context.getBeansOfType(HibernatePropertiesCustomizer::class.java).isEmpty() shouldBe true
                }
            }
        }

        `when`("운영 설정 파일을 읽으면") {
            then("환경변수 하나로 끌 수 있다 — 문제가 보이면 코드를 되돌리지 않고 예전 방식으로 복귀한다") {
                FileSystemResource("src/main/resources/application.yml").getContentAsString(Charsets.UTF_8) shouldContain
                    "release-connection-after-transaction: \${JPA_RELEASE_CONNECTION_AFTER_TRANSACTION:true}"
            }
        }
    }
})
