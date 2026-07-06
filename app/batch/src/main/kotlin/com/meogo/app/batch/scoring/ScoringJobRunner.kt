package com.meogo.app.batch.scoring

import com.meogo.core.research.FoodScoringStatus
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner

class ScoringJobRunner(
    private val job: AvoidanceScoringJob,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(ScoringJobRunner::class.java)

    override fun run(args: ApplicationArguments) {
        val results = job.run()
        val scored = results.count { it.status == FoodScoringStatus.SCORED }
        val failed = results.count { it.status == FoodScoringStatus.FAILED }
        logger.info("기피성분 스코어링 잡 완료 total={} scored={} failed={}", results.size, scored, failed)
    }
}
