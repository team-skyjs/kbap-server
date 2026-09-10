package com.kbap.api

import com.kbap.api.notification.FakePushSender
import com.kbap.common.domain.notification.PushDispatchService
import com.kbap.common.port.push.PushSender
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext

@IntegrationTest
class KbapApiApplicationTests : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var context: ApplicationContext

    init {
        given("kbap-api 애플리케이션") {
            `when`("스프링 컨텍스트를 로드하면") {
                then("정상적으로 기동된다") {
                }

                then("푸시 파이프라인이 조립된다 — 통합 테스트 컨텍스트에서는 페이크 PushSender 가 우선한다") {
                    context.getBean(PushSender::class.java).shouldBeInstanceOf<FakePushSender>()
                    context.getBean(PushDispatchService::class.java).shouldNotBeNull()
                }
            }
        }
    }
}
