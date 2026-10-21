package com.kbap.api.core.config

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.FileSystemResource

class JpaConnectionConfigTest : BehaviorSpec({

    val runner = ApplicationContextRunner().withUserConfiguration(JpaConnectionConfig::class.java)

    fun deployedOpenInView(): Boolean? {
        val environment = StandardEnvironment()
        environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        YamlPropertySourceLoader().load("deployed", FileSystemResource("src/main/resources/application.yml")).forEach(environment.propertySources::addLast)
        return Binder.get(environment).bind("spring.jpa.open-in-view", Boolean::class.java).orElse(null)
    }

    given("배포 설정의 open-in-view") {
        `when`("운영 설정 파일(application.yml)을 환경변수 없이 바인딩하면") {
            then("false 다 — 영속성 컨텍스트는 트랜잭션 단위다(KB-727)") {
                deployedOpenInView() shouldBe false
                FileSystemResource("src/main/resources/application.yml").getContentAsString(Charsets.UTF_8) shouldContain
                    "open-in-view: \${JPA_OPEN_IN_VIEW:false}"
            }
        }
    }

    given("open-in-view 를 되돌렸을 때의 착지점") {
        `when`("open-in-view=true 면") {
            then("트랜잭션이 끝나면 커넥션을 돌려주는 설정(#354)이 되살아난다 — 요청이 끝날 때까지 커넥션을 쥐는 상태로 떨어지지 않는다") {
                runner.withPropertyValues("spring.jpa.open-in-view=true").run { context ->
                    val properties = mutableMapOf<String, Any>()
                    context.getBean(HibernatePropertiesCustomizer::class.java).customize(properties)

                    properties["hibernate.connection.handling_mode"] shouldBe "DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION"
                }
            }
        }

        `when`("open-in-view 가 false 거나 없으면") {
            then("설정을 걸지 않는다 — Hibernate 기본(HOLD)으로, 영속성 컨텍스트가 트랜잭션 단위라 그걸로 충분하다") {
                runner.withPropertyValues("spring.jpa.open-in-view=false").run { context ->
                    context.getBeansOfType(HibernatePropertiesCustomizer::class.java).isEmpty() shouldBe true
                }
                runner.run { context ->
                    context.getBeansOfType(HibernatePropertiesCustomizer::class.java).isEmpty() shouldBe true
                }
            }
        }
    }
})
