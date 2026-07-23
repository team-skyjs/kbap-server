package com.kbap.application.foodimage

import com.kbap.core.food.FoodImageBatchClient
import com.kbap.domain.food.FoodJpaRepository
import com.kbap.domain.food.ImageBatchItemJpaRepository
import com.kbap.domain.food.ImageBatchJpaRepository
import com.kbap.domain.food.model.ImageBatch
import com.kbap.domain.food.model.ImageBatchItem
import com.kbap.domain.food.model.ImageBatchItemStatus
import com.kbap.domain.food.model.ImageBatchStatus
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

// 제출(KB-226): claim-first — 외부 호출 전에 DB 에 SUBMITTING 배치 + PENDING 항목을 선점 커밋한다.
// - 제출 도중 중단: 항목이 PENDING 으로 남아 재제출이 차단되고, 오래된 SUBMITTING 은 회수 틱이 FAILED 로 복구.
// - 동시 제출: 선점 커밋이 경합을 좁히고, pending_food_id 생성열 UNIQUE 가 최후 방어(경합 패자는 그 청크만 스킵).
// 외부 호출(OpenAI 업로드·배치 생성)은 트랜잭션 밖 — 헌법 추가 제약.
@Service
class FoodImageBatchSubmitService(
    private val foodRepository: FoodJpaRepository,
    private val batchRepository: ImageBatchJpaRepository,
    private val itemRepository: ImageBatchItemJpaRepository,
    private val client: FoodImageBatchClient,
    private val properties: FoodImageProperties,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val metaTransaction = TransactionTemplate(transactionManager)

    fun submitMissingImages(): FoodImageSubmitResult {
        val candidates = foodRepository.findImageCandidates()
        var submittedBatchCount = 0
        var submittedFoodCount = 0
        candidates.chunked(properties.batchSize).forEach { chunk ->
            val batch = try {
                metaTransaction.execute {
                    val claimed = batchRepository.save(ImageBatch(promptVersion = properties.promptVersion, model = properties.model))
                    itemRepository.saveAll(chunk.map { ImageBatchItem(batchId = claimed.id, foodId = it.id) })
                    claimed
                }!!
            } catch (e: DataIntegrityViolationException) {
                // 다른 요청/인스턴스가 같은 음식을 먼저 선점(UNIQUE 충돌) — 이 청크는 이미 제출 진행 중이므로 스킵
                log.warn("이미지 제출 선점 경합 — 청크 스킵 foodIds={}", chunk.map { it.id }, e)
                return@forEach
            }
            try {
                val entries = chunk.map {
                    FoodImageBatchClient.Entry(customId = it.id.toString(), prompt = properties.promptFor(it.koreanName))
                }
                val openaiBatchId = client.submit(entries)
                metaTransaction.executeWithoutResult {
                    batchRepository.save(batch.apply { markSubmitted(openaiBatchId) })
                }
            } catch (e: Exception) {
                // 외부 제출 실패 — 선점을 즉시 해제(FAILED)해 음식을 다음 제출 후보로 되돌린다
                metaTransaction.executeWithoutResult {
                    itemRepository.findByBatchIdAndItemStatus(batch.id, ImageBatchItemStatus.PENDING)
                        .forEach { item -> itemRepository.save(item.apply { fail("제출 실패: ${e.message}") }) }
                    batchRepository.save(batch.apply { close(ImageBatchStatus.FAILED) })
                }
                throw e
            }
            submittedBatchCount++
            submittedFoodCount += chunk.size
        }
        return FoodImageSubmitResult(submittedBatchCount, submittedFoodCount)
    }
}

data class FoodImageSubmitResult(
    val submittedBatchCount: Int,
    val submittedFoodCount: Int,
)
