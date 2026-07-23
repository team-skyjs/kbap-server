package com.kbap.application.foodimage

import com.kbap.core.food.FoodImageBatchClient
import com.kbap.core.llm.LlmCallCostIncurred
import com.kbap.core.storage.StorageObjectStore
import com.kbap.domain.food.FoodJpaRepository
import com.kbap.domain.food.ImageBatchItemJpaRepository
import com.kbap.domain.food.ImageBatchJpaRepository
import com.kbap.domain.food.model.ImageBatch
import com.kbap.domain.food.model.ImageBatchItem
import com.kbap.domain.food.model.ImageBatchItemStatus
import com.kbap.domain.food.model.ImageBatchStatus
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.RoundingMode

// 회수(KB-226): SUBMITTED 배치 폴링 → 완료분 스트리밍 파싱 → 스토리지 저장 → imageRef 갱신 + 수렴 전이 → 마감.
// seam 3분할(상태 조회 / 바이트 이동 / DB 전이) — 부하 실측 후 바이트 이동만 워커로 들어낼 수 있는 구조.
// 외부 호출(OpenAI·스토리지)은 트랜잭션 밖, DB 전이는 항목당 짧은 트랜잭션 — 중단돼도 처리분은 커밋 유지(멱등 재회수).
@Service
class FoodImageBatchCollectService(
    private val batchRepository: ImageBatchJpaRepository,
    private val itemRepository: ImageBatchItemJpaRepository,
    private val foodRepository: FoodJpaRepository,
    private val client: FoodImageBatchClient,
    private val storageObjectStore: StorageObjectStore,
    private val eventPublisher: ApplicationEventPublisher,
    private val properties: FoodImageProperties,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val itemTransaction = TransactionTemplate(transactionManager)

    fun collectSubmitted() {
        batchRepository.findByBatchStatus(ImageBatchStatus.SUBMITTED).forEach { batch ->
            runCatching { collect(batch) }
                .onFailure { log.error("이미지 배치 회수 실패 — 다음 틱에 재시도 batchId={}", batch.id, it) }
        }
    }

    private fun collect(batch: ImageBatch) {
        val poll = client.status(batch.openaiBatchId)
        when (poll.state) {
            FoodImageBatchClient.State.IN_PROGRESS -> Unit
            FoodImageBatchClient.State.COMPLETED -> collectCompleted(batch, poll)
            FoodImageBatchClient.State.FAILED,
            FoodImageBatchClient.State.EXPIRED,
            -> closeFailed(batch, poll.state)
        }
    }

    private fun collectCompleted(batch: ImageBatch, poll: FoodImageBatchClient.BatchPoll) {
        // PENDING 만 처리 대상 — 이미 DONE 인 항목은 건너뛴다(중단 후 재회수 멱등).
        val pendingByFoodId = itemRepository.findByBatchIdAndItemStatus(batch.id, ImageBatchItemStatus.PENDING)
            .associateBy { it.foodId }
            .toMutableMap()
        poll.outputFileId?.let { fileId ->
            client.streamResults(fileId) { result ->
                val foodId = result.customId.toLongOrNull() ?: return@streamResults
                val item = pendingByFoodId.remove(foodId) ?: return@streamResults
                handleResult(item, result)
            }
        }
        // 결과 줄이 없던 항목은 실패분(error file) — 상세 대신 일괄 실패 마감해 배치를 닫는다(재제출 경로로 복구).
        pendingByFoodId.values.forEach { item ->
            saveItem(item) { it.fail("배치 결과 누락(errorFileId=${poll.errorFileId})") }
        }
        itemTransaction.executeWithoutResult {
            batchRepository.save(batch.apply { close(ImageBatchStatus.COLLECTED) })
        }
    }

    private fun handleResult(item: ImageBatchItem, result: FoodImageBatchClient.Result) {
        val bytes = result.bytes
        if (result.errorMessage != null || bytes == null) {
            saveItem(item) { it.fail(result.errorMessage ?: "이미지 데이터 없음") }
            return
        }
        val food = foodRepository.findById(item.foodId).orElse(null)
        if (food == null) {
            saveItem(item) { it.fail("음식이 삭제되어 건너뜀") }
            return
        }
        val key = storageKeyOf(item.foodId)
        storageObjectStore.put(key, bytes, "image/png")
        itemTransaction.executeWithoutResult {
            food.attachImage(key)
            foodRepository.save(food)
            itemRepository.save(item.apply { done(key) })
        }
        result.usage?.let { publishCost(it) }
    }

    private fun closeFailed(batch: ImageBatch, state: FoodImageBatchClient.State) {
        itemRepository.findByBatchIdAndItemStatus(batch.id, ImageBatchItemStatus.PENDING).forEach { item ->
            saveItem(item) { it.fail("배치 $state") }
        }
        itemTransaction.executeWithoutResult {
            batchRepository.save(batch.apply { close(ImageBatchStatus.FAILED) })
        }
    }

    private fun saveItem(item: ImageBatchItem, mutate: (ImageBatchItem) -> Unit) {
        itemTransaction.executeWithoutResult {
            mutate(item)
            itemRepository.save(item)
        }
    }

    private fun publishCost(usage: FoodImageBatchClient.Usage) {
        val costUsd = BigDecimal.valueOf(usage.inputTokens)
            .multiply(BigDecimal.valueOf(properties.inputUsdPerMillionTokens))
            .add(
                BigDecimal.valueOf(usage.outputTokens)
                    .multiply(BigDecimal.valueOf(properties.outputUsdPerMillionTokens)),
            )
            .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP)
        eventPublisher.publishEvent(
            LlmCallCostIncurred(
                modelName = properties.model,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                costUsd = costUsd,
                costKrw = costUsd.multiply(BigDecimal.valueOf(properties.usdToKrw)).setScale(2, RoundingMode.HALF_UP),
            ),
        )
    }

    companion object {
        // 결정적 키 — 재회수·재생성이 put 덮어쓰기로 자연 멱등. 음식 사진은 환경 공용이라 무접두(KB-171).
        fun storageKeyOf(foodId: Long): String = "images/food/$foodId.png"
    }
}
