package com.kbap.batch

import com.kbap.common.domain.notification.PushDispatchService
import com.kbap.common.port.push.PushSender
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext

@BatchIntegrationTest
class KbapBatchApplicationTests : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var context: ApplicationContext

    init {
        given("kbap-batch 애플리케이션") {
            `when`("스프링 컨텍스트를 로드하면") {
                then("정상적으로 기동된다") {
                }

                then("푸시 파이프라인(PushSender·PushDispatchService)이 조립된다") {
                    context.getBean(PushSender::class.java).shouldNotBeNull()
                    context.getBean(PushDispatchService::class.java).shouldNotBeNull()
                }
            }
        }
    }
}
