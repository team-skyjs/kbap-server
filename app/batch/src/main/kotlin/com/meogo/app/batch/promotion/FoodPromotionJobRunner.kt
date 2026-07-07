package com.meogo.app.batch.promotion

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner

class FoodPromotionJobRunner(
    private val job: FoodPromotionJob,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(FoodPromotionJobRunner::class.java)

    override fun run(args: ApplicationArguments) {
        val result = job.run()
        logger.info(
            "음식 승격 잡 완료 total={} promoted={} failed={}",
            result.total,
            result.promoted,
            result.failed,
        )
    }
}
