package com.kbap.batch.processedfood

import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import com.kbap.common.port.publicdata.FoodMakerLookup
import com.kbap.common.port.publicdata.HaccpProduct
import com.kbap.common.port.publicdata.HaccpProductSource
import com.kbap.common.port.publicdata.PublicDataCallCounter
import org.slf4j.LoggerFactory
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
    private val makerLookupMaxConsecutiveFailures: Int = DEFAULT_MAX_CONSECUTIVE_FAILURES,
    private val counter: PublicDataCallCounter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun mirror(): ProcessedFoodMirrorSummary {
        val startedAt = System.nanoTime()
        val attemptsBefore = counter.httpAttempts()
        val now = LocalDateTime.now(clock)
        val seen = mutableSetOf<String>()
        val perType = linkedMapOf<String, Int>()
        val failedTypes = mutableListOf<String>()
        val failedPages = mutableListOf<String>()
        var inserted = 0
        var changed = 0
        var unchanged = 0
        var crossTypeDuplicates = 0
        var skipped = 0
        var sourceConflicts = 0
        var promotedFromC002 = 0

        foodTypes.ifEmpty { listOf(null) }.forEach { type ->
            val label = type ?: ALL_TYPES
            var fetched = 0
            var pageNo = 1
            var lastPage: Int? = null
            var consecutiveFailures = 0
            while (pageNo <= (lastPage ?: MAX_PAGES)) {
                val page = try {
                    source.getPage(type, pageNo, pageSize)
                } catch (e: Exception) {
                    consecutiveFailures++
                    log.warn("가공식품 미러링 — 유형 {} page={} 수집 실패 이유={}", label, pageNo, e.message)
                    if (lastPage == null || consecutiveFailures >= MAX_CONSECUTIVE_PAGE_FAILURES) {
                        failedTypes += label
                        log.warn("가공식품 미러링 — 유형 {} 수집 중단 page={}", label, pageNo)
                        break
                    }
                    failedPages += "$label#$pageNo"
                    pageNo++
                    continue
                }
                consecutiveFailures = 0
                if (page.items.isEmpty()) break
                lastPage = minOf(MAX_PAGES, (page.totalCount + pageSize - 1) / pageSize)
                fetched += page.items.size
                val fresh = page.items.filter { seen.add(it.reportNo) }
                crossTypeDuplicates += page.items.size - fresh.size
                val outcome = transaction.execute { upsert(fresh, now) }!!
                inserted += outcome.inserted
                skipped += outcome.skipped
                sourceConflicts += outcome.sourceConflicts
                promotedFromC002 += outcome.promotedFromC002
                changed += outcome.changed
                unchanged += outcome.unchanged
                pageNo++
            }
            perType[label] = fetched
        }

        val maker = supplementMakers(now)
        val summary = ProcessedFoodMirrorSummary(
            perType = perType,
            distinctProducts = seen.size,
            inserted = inserted,
            changed = changed,
            unchanged = unchanged,
            crossTypeDuplicates = crossTypeDuplicates,
            skippedRows = skipped,
            sourceConflicts = sourceConflicts,
            promotedFromC002 = promotedFromC002,
            failedTypes = failedTypes,
            failedPages = failedPages,
            makerLookups = maker.calls,
            makerFilled = maker.filled,
            makerLookupFailures = maker.failures,
            makerLookupAborted = maker.aborted,
            apiCalls = (counter.httpAttempts() - attemptsBefore).toInt(),
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
        )
        log.info("가공식품 미러링 완료 {}", summary)
        return summary
    }

    private fun upsert(products: List<HaccpProduct>, now: LocalDateTime): UpsertOutcome {
        val (storable, oversized) = products.partition { it.reportNo.length <= ProcessedFoodProduct.REPORT_NO_LENGTH }
        oversized.forEach { log.warn("가공식품 미러링 — 품목보고번호가 {}자를 넘어 건너뜁니다 length={} name={}", ProcessedFoodProduct.REPORT_NO_LENGTH, it.reportNo.length, it.productName.take(30)) }
        if (storable.isEmpty()) return UpsertOutcome(0, 0, 0, oversized.size, 0, 0)
        val existing = repository.findAllByReportNoIn(storable.map { it.reportNo }).associateBy { it.reportNo }
        var inserted = 0
        var changed = 0
        var unchanged = 0
        var sourceConflicts = 0
        var promotedFromC002 = 0
        storable.forEach { product ->
            val snapshot = product.toSnapshot()
            val row = existing[product.reportNo]
            when {
                row == null -> { repository.save(ProcessedFoodProduct.collect(snapshot, now)); inserted++ }
                row.source == ProcessedFoodProduct.SOURCE_C002 -> {
                    row.promoteToHaccp(snapshot, now)
                    promotedFromC002++
                    log.info("가공식품 미러링 — C002 행을 HACCP 값으로 갱신하고 HACCP 로 승격 reportNo={}", row.reportNo)
                }
                row.source != ProcessedFoodProduct.SOURCE_HACCP -> {
                    sourceConflicts++
                    log.warn("가공식품 미러링 — 같은 품목보고번호의 {} 행이 있어 HACCP 행으로 덮어쓰지 않고 건너뜁니다 reportNo={}", row.source, row.reportNo)
                }
                row.refresh(snapshot, now) -> changed++
                else -> unchanged++
            }
        }
        return UpsertOutcome(inserted, changed, unchanged, oversized.size, sourceConflicts, promotedFromC002)
    }

    private fun supplementMakers(now: LocalDateTime): MakerOutcome {
        val targets = transaction.execute { repository.findMakerLookupTargets(makerLookupLimit).map { it.id to it.reportNo } }!!
        var calls = 0
        var filled = 0
        var failures = 0
        var consecutiveFailures = 0
        for ((id, reportNo) in targets) {
            if (consecutiveFailures >= makerLookupMaxConsecutiveFailures) {
                log.warn("가공식품 미러링 — 제조사 보완 조회가 {}회 연달아 실패해 이번 회 보완을 멈춥니다(남은 {}행은 다음 회)", consecutiveFailures, targets.size - calls)
                return MakerOutcome(calls, filled, failures, aborted = true)
            }
            val maker = try {
                calls++
                makerLookup.getMakerOrNull(reportNo)
            } catch (e: Exception) {
                failures++
                consecutiveFailures++
                log.warn("가공식품 미러링 — 제조사 보완 조회 실패 reportNo={} 이유={}", reportNo, e.message)
                continue
            }
            consecutiveFailures = 0
            transaction.executeWithoutResult { repository.findById(id).ifPresent { it.supplementMaker(maker, now) } }
            if (maker != null) filled++
        }
        return MakerOutcome(calls, filled, failures, aborted = consecutiveFailures >= makerLookupMaxConsecutiveFailures)
    }

    private fun HaccpProduct.toSnapshot() = ProcessedFoodSnapshot(
        reportNo = reportNo,
        productName = productName.take(ProcessedFoodProduct.NAME_LENGTH),
        productGb = productGb?.take(ProcessedFoodProduct.PRODUCT_GB_LENGTH),
        foodType = foodType?.take(ProcessedFoodProduct.FOOD_TYPE_LENGTH),
        foodTypeState = foodTypeState?.take(ProcessedFoodProduct.FOOD_TYPE_LENGTH),
        manufacturer = manufacturer?.take(ProcessedFoodProduct.MAKER_LENGTH),
        seller = seller?.take(ProcessedFoodProduct.MAKER_LENGTH),
        barcode = barcode?.take(ProcessedFoodProduct.BARCODE_LENGTH),
        capacity = capacity?.take(ProcessedFoodProduct.CAPACITY_LENGTH),
        rawMaterials = rawMaterials,
        allergens = allergens?.take(ProcessedFoodProduct.ALLERGENS_LENGTH),
        nutrients = nutrients,
        imageUrl = imageUrl?.take(ProcessedFoodProduct.URL_LENGTH),
        metaImageUrl = metaImageUrl?.take(ProcessedFoodProduct.URL_LENGTH),
    )

    private data class UpsertOutcome(val inserted: Int, val changed: Int, val unchanged: Int, val skipped: Int, val sourceConflicts: Int, val promotedFromC002: Int)

    private data class MakerOutcome(val calls: Int, val filled: Int, val failures: Int, val aborted: Boolean)

    private companion object {
        const val MAX_PAGES = 500
        const val ALL_TYPES = "전체"
        const val MAX_CONSECUTIVE_PAGE_FAILURES = 3
        const val DEFAULT_MAX_CONSECUTIVE_FAILURES = 5
    }
}

data class ProcessedFoodMirrorSummary(
    val perType: Map<String, Int>,
    val distinctProducts: Int,
    val inserted: Int,
    val changed: Int,
    val unchanged: Int,
    val crossTypeDuplicates: Int,
    val skippedRows: Int,
    val sourceConflicts: Int,
    val promotedFromC002: Int,
    val failedTypes: List<String>,
    val failedPages: List<String>,
    val makerLookups: Int,
    val makerFilled: Int,
    val makerLookupFailures: Int,
    val makerLookupAborted: Boolean,
    val apiCalls: Int,
    val elapsedMillis: Long,
) {
    fun isPartial(): Boolean = failedTypes.isNotEmpty() || failedPages.isNotEmpty() || makerLookupAborted
}
