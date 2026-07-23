package com.kbap.application.foodimage

import com.kbap.core.food.FoodImageBatchClient
import com.kbap.domain.food.FoodJpaRepository
import com.kbap.domain.food.ImageBatchItemJpaRepository
import com.kbap.domain.food.ImageBatchJpaRepository
import com.kbap.domain.food.model.ImageBatch
import com.kbap.domain.food.model.ImageBatchItem
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

// 제출(KB-226): 후보 선정 → 배치 단위 분할 → OpenAI 제출 → 메타 기록. 생성 완료를 기다리지 않는다.
// 후보 조건(imageRef 부재 + PENDING 미포함)이 중복 제출 가드를 겸하므로 재실행은 빠진 것만 다시 제출한다(멱등).
@Service
class FoodImageBatchSubmitService(
    private val foodRepository: FoodJpaRepository,
    private val batchRepository: ImageBatchJpaRepository,
    private val itemRepository: ImageBatchItemJpaRepository,
    private val client: FoodImageBatchClient,
    private val properties: FoodImageProperties,
    transactionManager: PlatformTransactionManager,
) {
    // 외부 호출(OpenAI 업로드·배치 생성)을 DB 트랜잭션 안에 두지 않는다 — 메타 기록만 짧은 쓰기.
    private val metaTransaction = TransactionTemplate(transactionManager)

    fun submitMissingImages(): FoodImageSubmitResult {
        val candidates = foodRepository.findImageCandidates()
        var submittedBatchCount = 0
        var submittedFoodCount = 0
        candidates.chunked(properties.batchSize).forEach { chunk ->
            val entries = chunk.map {
                FoodImageBatchClient.Entry(customId = it.id.toString(), prompt = properties.promptFor(it.koreanName))
            }
            val openaiBatchId = client.submit(entries)
            metaTransaction.executeWithoutResult {
                val batch = batchRepository.save(
                    ImageBatch(
                        openaiBatchId = openaiBatchId,
                        promptVersion = properties.promptVersion,
                        model = properties.model,
                    ),
                )
                itemRepository.saveAll(chunk.map { ImageBatchItem(batchId = batch.id, foodId = it.id) })
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
