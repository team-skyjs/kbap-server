package com.kbap.batch.outbox

import com.kbap.batch.BatchIntegrationTest
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.batch.core.job.Job
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier

@BatchIntegrationTest
class FoodContentOutboxBatchConfigTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    @Qualifier("foodContentOutboxPublishJob")
    private lateinit var job: Job

    init {
        given("상시 기동된 배치 애플리케이션") {
            `when`("부팅 자동 실행이 꺼진 채로 기동하면") {
                then("실행 가능한 잡이 구성된다") {
                    job.name shouldBe "foodContentOutboxPublishJob"
                }
            }
        }
    }
}
