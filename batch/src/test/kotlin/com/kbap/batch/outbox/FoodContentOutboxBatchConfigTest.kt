package com.kbap.batch.outbox

import com.kbap.common.core.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.batch.core.job.Job
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest(
    properties = [
        "spring.batch.job.enabled=true",
        "kbap.batch.food-content-outbox.queue-url=https://sqs.ap-northeast-2.amazonaws.com/123456789012/food-content",
    ],
)
@Import(MySqlContainerConfig::class)
class FoodContentOutboxBatchConfigTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    @Qualifier("foodContentOutboxPublishJob")
    private lateinit var job: Job

    init {
        given("아웃박스 발행 잡 활성화") {
            `when`("배치 애플리케이션을 기동하면") {
                then("실행 가능한 잡이 구성된다") {
                    job.name shouldBe "foodContentOutboxPublishJob"
                }
            }
        }
    }
}
