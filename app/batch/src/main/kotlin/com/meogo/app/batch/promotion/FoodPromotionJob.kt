package com.meogo.app.batch.promotion

import com.meogo.core.food.FoodRepository
import com.meogo.core.research.candidate.FoodCandidate
import com.meogo.core.research.candidate.FoodCandidateRepository
import org.slf4j.LoggerFactory

class FoodPromotionJob(
    private val foodCandidateRepository: FoodCandidateRepository,
    private val foodRepository: FoodRepository,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) {
    private val logger = LoggerFactory.getLogger(FoodPromotionJob::class.java)
    private val mapper = CandidatePromotionMapper()

    fun run(): PromotionResult {
        var promoted = 0
        var failed = 0
        var lastId = CURSOR_START
        while (true) {
            val candidates = foodCandidateRepository.findPromotable(lastId, chunkSize)
            if (candidates.isEmpty()) {
                return PromotionResult(promoted = promoted, failed = failed)
            }
            for (candidate in candidates) {
                lastId = candidate.id!!
                if (!candidate.isComplete()) {
                    continue
                }
                if (promote(candidate)) promoted++ else failed++
            }
        }
    }

    private fun promote(candidate: FoodCandidate): Boolean {
        val candidateId = candidate.id!!
        return try {
            val existing = foodRepository.findByKoreanName(candidate.koreanName)
            val foodId = existing?.id ?: foodRepository.save(mapper.toFood(candidate)).id!!
            foodCandidateRepository.markPublished(candidateId, foodId)
            true
        } catch (exception: Exception) {
            logger.warn(
                "food 승격 실패 candidateId={} koreanName={} reason={}",
                candidateId,
                candidate.koreanName,
                exception.message,
            )
            false
        }
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 100
        private const val CURSOR_START = 0L
    }
}

data class PromotionResult(
    val promoted: Int,
    val failed: Int,
) {
    val total: Int = promoted + failed
}
