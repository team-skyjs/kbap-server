package com.kbap.api

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension

@IntegrationTest
class KbapApiApplicationTests : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    init {
        given("kbap-api 애플리케이션") {
            `when`("스프링 컨텍스트를 로드하면") {
                then("정상적으로 기동된다") {
                }
            }
        }
    }
}
