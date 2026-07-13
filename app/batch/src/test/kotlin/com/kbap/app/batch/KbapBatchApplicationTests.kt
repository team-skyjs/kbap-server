package com.kbap.app.batch

import com.kbap.core.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class KbapBatchApplicationTests : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    init {
        given("kbap-batch 애플리케이션") {
            `when`("스프링 컨텍스트를 로드하면") {
                then("정상적으로 기동된다") {
                }
            }
        }
    }
}
