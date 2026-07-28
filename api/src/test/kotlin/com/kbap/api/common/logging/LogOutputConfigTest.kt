package com.kbap.api.common.logging

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class LogOutputConfigTest : BehaviorSpec({

    given("로그 출력 설정") {
        `when`("클래스패스의 커스텀 logback 설정을 확인하면") {
            // 커스텀 logback 설정이 있으면 Boot 은 패턴 인코더를 쓰고
            // logging.structured.format.console(staging·prod 의 JSON) 을 무시한다.
            then("존재하지 않는다 — Boot 기본 초기화가 텍스트·JSON 포맷을 모두 처리한다") {
                val loader = LogOutputConfigTest::class.java.classLoader
                loader.getResource("logback-spring.xml") shouldBe null
                loader.getResource("logback.xml") shouldBe null
            }
        }
    }
})
