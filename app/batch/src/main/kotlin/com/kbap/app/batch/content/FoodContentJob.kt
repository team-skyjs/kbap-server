package com.kbap.app.batch.content

import com.kbap.domain.food.FoodContentBatchService
import com.kbap.domain.food.model.Food
import org.slf4j.LoggerFactory

// 음식 단위 콘텐츠 파이프라인(KB-182) — INCOMPLETE 음식을 id 키셋 청크로 소진하며 1건씩 처리한다.
// 한 음식의 4작업을 수행하고(작업별 메서드로 구분), 저장·전이는 completeContent(짧은 트랜잭션)에 위임한다.
// LLM 등 외부 호출은 작업 메서드 안(트랜잭션 밖)에서 일어난다. 한 건 처리 실패는 그 건에 격리되고
// 나머지 청크는 계속 처리된다 — 실패 건은 INCOMPLETE 로 남아 다음 실행에서 재시도된다.
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
        generateImage(food)
        generateDescription(food)
        translateNames(food)
        val hasAvoidanceMapping = mapAvoidance(food)
        return foodContentBatchService.completeContent(food, hasAvoidanceMapping)
    }

    // 사진 생성 스텝 — KB-184 에서 imageRef 를 채운다.
    private fun generateImage(food: Food) {
    }

    // 설명 생성 스텝 — KB-183 에서 ko description 을 채운다.
    private fun generateDescription(food: Food) {
    }

    // 이름·설명 번역 스텝 — KB-183 에서 9개 대상 언어 번역을 채운다.
    private fun translateNames(food: Food) {
    }

    // 기피성분 매핑·맵기 스텝 — KB-209 에서 매핑을 쓰고 존재 여부를 반환한다.
    private fun mapAvoidance(food: Food): Boolean = false
}
