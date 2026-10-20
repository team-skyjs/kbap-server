package com.kbap.batch.processedfood

import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import com.kbap.common.port.publicdata.FoodMakerLookup
import com.kbap.common.port.publicdata.HaccpProduct
import com.kbap.common.port.publicdata.HaccpProductSource
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDateTime

class ProcessedFoodMirror(
    private val source: HaccpProductSource,
    private val makerLookup: FoodMakerLookup,
    private val repository: ProcessedFoodProductJpaRepository,
    private val transaction: TransactionTemplate,
    private val clock: Clock,
    private val foodTypes: List<String>,
    private val pageSize: Int,
    private val makerLookupLimit: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun mirror(): ProcessedFoodMirrorSummary {
        val startedAt = System.nanoTime()
        val now = LocalDateTime.now(clock)
        val seen = mutableSetOf<String>()
        val perType = linkedMapOf<String, Int>()
        val failedTypes = mutableListOf<String>()
        var calls = 0
        var inserted = 0
        var changed = 0
        var unchanged = 0
        var crossTypeDuplicates = 0

        foodTypes.forEach { type ->
            var fetched = 0
            var pageNo = 1
            try {
                while (pageNo <= MAX_PAGES) {
                    val page = source.getPage(type, pageNo, pageSize)
                    calls++
                    if (page.items.isEmpty()) break
                    fetched += page.items.size
                    val fresh = page.items.filter { seen.add(it.reportNo) }
                    crossTypeDuplicates += page.items.size - fresh.size
                    val outcome = transaction.execute { upsert(fresh, now) }!!
                    inserted += outcome.inserted
                    changed += outcome.changed
                    unchanged += outcome.unchanged
                    if (fetched >= page.totalCount) break
                    pageNo++
                }
            } catch (e: Exception) {
                failedTypes += type
                log.warn("가공식품 미러링 — 유형 {} 수집 중단 page={} 이유={}", type, pageNo, e.message)
            }
            perType[type] = fetched
        }

        val maker = supplementMakers(now)
        calls += maker.calls
        val summary = ProcessedFoodMirrorSummary(
            perType = perType,
            distinctProducts = seen.size,
            inserted = inserted,
            changed = changed,
            unchanged = unchanged,
            crossTypeDuplicates = crossTypeDuplicates,
            failedTypes = failedTypes,
            makerLookups = maker.calls,
            makerFilled = maker.filled,
            makerLookupFailures = maker.failures,
            apiCalls = calls,
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
        )
        log.info("가공식품 미러링 완료 {}", summary)
        return summary
    }

    private fun upsert(products: List<HaccpProduct>, now: LocalDateTime): UpsertOutcome {
        if (products.isEmpty()) return UpsertOutcome(0, 0, 0)
        val existing = repository.findAllByReportNoIn(products.map { it.reportNo }).associateBy { it.reportNo }
        var inserted = 0
        var changed = 0
        var unchanged = 0
        products.forEach { product ->
            val snapshot = product.toSnapshot()
            val row = existing[product.reportNo]
            when {
                row == null -> { repository.save(ProcessedFoodProduct.collect(snapshot, now)); inserted++ }
                row.refresh(snapshot, now) -> changed++
                else -> unchanged++
            }
        }
        return UpsertOutcome(inserted, changed, unchanged)
    }

    private fun supplementMakers(now: LocalDateTime): MakerOutcome {
        val targets = transaction.execute { repository.findMakerLookupTargets(PageRequest.of(0, makerLookupLimit)).filter { it.needsMakerLookup() }.map { it.id to it.reportNo } }!!
        var calls = 0
        var filled = 0
        var failures = 0
        targets.forEach { (id, reportNo) ->
            val maker = try {
                calls++
                makerLookup.getMakerOrNull(reportNo)
            } catch (e: Exception) {
                failures++
                log.warn("가공식품 미러링 — 제조사 보완 조회 실패 reportNo={} 이유={}", reportNo, e.message)
                return@forEach
            }
            transaction.executeWithoutResult { repository.findById(id).ifPresent { it.supplementMaker(maker, now) } }
            if (maker != null) filled++
        }
        return MakerOutcome(calls, filled, failures)
    }

    private fun HaccpProduct.toSnapshot() = ProcessedFoodSnapshot(
        reportNo = reportNo,
        productName = productName,
        productGb = productGb,
        foodType = foodType,
        foodTypeState = foodTypeState,
        manufacturer = manufacturer,
        seller = seller,
        barcode = barcode,
        capacity = capacity,
        rawMaterials = rawMaterials,
        allergens = allergens,
        nutrients = nutrients,
        imageUrl = imageUrl,
        metaImageUrl = metaImageUrl,
    )

    private data class UpsertOutcome(val inserted: Int, val changed: Int, val unchanged: Int)

    private data class MakerOutcome(val calls: Int, val filled: Int, val failures: Int)

    private companion object {
        const val MAX_PAGES = 500
    }
}

data class ProcessedFoodMirrorSummary(
    val perType: Map<String, Int>,
    val distinctProducts: Int,
    val inserted: Int,
    val changed: Int,
    val unchanged: Int,
    val crossTypeDuplicates: Int,
    val failedTypes: List<String>,
    val makerLookups: Int,
    val makerFilled: Int,
    val makerLookupFailures: Int,
    val apiCalls: Int,
    val elapsedMillis: Long,
)
