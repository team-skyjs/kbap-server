package com.kbap.batch.notification.suggestion

import com.kbap.batch.BatchIntegrationTest
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.batch.core.job.Job
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier

@BatchIntegrationTest
class ScanSuggestionPushBatchConfigTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    @Qualifier("scanSuggestionLunchPushJob")
    private lateinit var lunchJob: Job

    @Autowired
    @Qualifier("scanSuggestionDinnerPushJob")
    private lateinit var dinnerJob: Job

    init {
        given("상시 기동된 배치 애플리케이션") {
            `when`("부팅 자동 실행이 꺼진 채로 기동하면") {
                then("점심·저녁 스캔 제안 발송 잡이 각각 구성된다") {
                    lunchJob.name shouldBe "scanSuggestionLunchPushJob"
                    dinnerJob.name shouldBe "scanSuggestionDinnerPushJob"
                }
            }
        }
    }
}
