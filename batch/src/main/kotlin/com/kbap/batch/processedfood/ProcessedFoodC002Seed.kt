package com.kbap.batch.processedfood

import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDateTime

class ProcessedFoodC002Seed(
    private val repository: ProcessedFoodProductJpaRepository,
    private val transaction: TransactionTemplate,
    private val clock: Clock,
    private val rows: List<Row> = bundledRows(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun seed(): ProcessedFoodC002SeedSummary {
        val summary = try {
            insertMissing(LocalDateTime.now(clock))
        } catch (e: Exception) {
            log.error("가공식품 C002 인기 상품 적재 실패 — 이번 회는 건너뛰고 HACCP 미러는 이어서 돈다", e)
            ProcessedFoodC002SeedSummary(inserted = 0, alreadyLoaded = 0, haccpConflicts = emptyList(), failure = e.javaClass.simpleName + ": " + e.message?.take(FAILURE_MESSAGE_LENGTH))
        }
        log.info("가공식품 C002 인기 상품 적재 완료 {}", summary)
        return summary
    }

    private fun insertMissing(now: LocalDateTime): ProcessedFoodC002SeedSummary =
        transaction.execute {
            val existing = repository.findAllByReportNoIn(rows.map { it.reportNo }).associateBy { it.reportNo }
            val conflicts = rows.mapNotNull { row -> existing[row.reportNo]?.takeIf { it.source != ProcessedFoodProduct.SOURCE_C002 }?.reportNo }
            conflicts.forEach { log.warn("가공식품 C002 적재 — 같은 품목보고번호의 HACCP 행이 있어 건너뜁니다 reportNo={}", it) }
            val fresh = rows.filter { it.reportNo !in existing }
            fresh.forEach { repository.save(ProcessedFoodProduct.collect(it.toSnapshot(), now, ProcessedFoodProduct.SOURCE_C002)) }
            repository.flush()
            ProcessedFoodC002SeedSummary(inserted = fresh.size, alreadyLoaded = rows.size - fresh.size - conflicts.size, haccpConflicts = conflicts)
        }!!

    data class Row(val reportNo: String, val productName: String, val foodType: String, val manufacturer: String, val rawMaterials: String) {
        fun toSnapshot() = ProcessedFoodSnapshot(
            reportNo = reportNo,
            productName = productName,
            productGb = null,
            foodType = foodType,
            foodTypeState = null,
            manufacturer = manufacturer,
            seller = null,
            barcode = null,
            capacity = null,
            rawMaterials = rawMaterials,
            allergens = null,
            nutrients = null,
            imageUrl = null,
            metaImageUrl = null,
        )
    }

    companion object {
        private const val RESOURCE = "/processed-food/c002-popular-products.tsv"
        private const val FAILURE_MESSAGE_LENGTH = 300

        fun bundledRows(): List<Row> =
            requireNotNull(ProcessedFoodC002Seed::class.java.getResourceAsStream(RESOURCE)) { RESOURCE }
                .bufferedReader(Charsets.UTF_8)
                .useLines { lines ->
                    lines.drop(1).filter { it.isNotBlank() }.map { line ->
                        val (reportNo, name, type, maker, raw) = line.split('\t')
                        Row(reportNo, name, type, maker, raw)
                    }.toList()
                }
    }
}

data class ProcessedFoodC002SeedSummary(val inserted: Int, val alreadyLoaded: Int, val haccpConflicts: List<String>, val failure: String? = null)
