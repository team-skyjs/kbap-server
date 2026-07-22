package com.kbap.app.batch.content

import com.kbap.domain.food.FoodContentBatchService
import com.kbap.domain.food.model.Food
import org.slf4j.LoggerFactory

// INCOMPLETE 음식을 id 키셋으로 청크 조회해 1건씩 순차 처리한다. 한 음식의 4작업을 순서대로 호출하고
// (이미 된 작업은 needsX 로 건너뜀), 각 작업 결과는 즉시 개별 커밋(saveProgress)해 뒤 작업이 실패해도
// 앞 작업이 남아 다음 실행에서 실패한 작업만 재시도한다. 한 건 처리 실패는 그 건에 격리되고 잡은 계속된다.
// 성능이 문제되면 이후 청크 단위 스레드풀(future/코루틴) 병렬화를 검토한다.
class FoodContentJob(
    private val foodContentBatchService: FoodContentBatchService,
    private val chunkSize: Int,
) {
    private val logger = LoggerFactory.getLogger(FoodContentJob::class.java)

    fun run() {
        var afterId: Long? = null
        var total = 0
        var transitioned = 0
        var failed = 0
        while (true) {
            val chunk = foodContentBatchService.getIncompleteFoods(afterId, chunkSize)
            if (chunk.isEmpty()) break
            for (food in chunk) {
                total++
                try {
                    if (process(food)) transitioned++
                } catch (exception: Exception) {
                    failed++
                    logger.warn("음식 콘텐츠 처리 실패 foodId={} message={}", food.id, exception.message, exception)
                }
            }
            afterId = chunk.last().id
        }
        logger.info("콘텐츠 파이프라인 잡 완료 total={} transitioned={} failed={}", total, transitioned, failed)
    }

    private fun process(food: Food): Boolean {
        if (food.needsImage()) {
            generateImage(food)
            foodContentBatchService.saveProgress(food)
        }
        if (food.needsDescription()) {
            generateDescription(food)
            foodContentBatchService.saveProgress(food)
        }
        if (food.needsNameTranslations() || food.needsDescriptionTranslations()) {
            translateContent(food)
            foodContentBatchService.saveProgress(food)
        }
        if (food.needsAvoidanceMapping()) {
            mapAvoidance(food)
            foodContentBatchService.saveProgress(food)
        }
        return foodContentBatchService.completeContent(food)
    }

    private fun generateImage(food: Food) {
    }

    private fun generateDescription(food: Food) {
    }

    private fun translateContent(food: Food) {
    }

    // KB-209: API 3개 호출·종합으로 food.avoidanceSubstances 를 채운다.
    private fun mapAvoidance(food: Food) {
    }
}
