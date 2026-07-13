package com.meogo.app.api
import com.meogo.core.testsupport.MySqlContainerConfig
import org.springframework.context.annotation.Import

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
@Import(MySqlContainerConfig::class)
class MeogoApiApplicationTests : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    init {
        given("meogo-api 애플리케이션") {
            `when`("스프링 컨텍스트를 로드하면") {
                then("정상적으로 기동된다") {
                }
            }
        }
    }
}
