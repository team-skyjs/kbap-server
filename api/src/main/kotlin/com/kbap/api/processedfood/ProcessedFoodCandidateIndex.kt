package com.kbap.api.processedfood

import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.domain.processedfood.dto.ProcessedFoodCandidateRow
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

@Component
class ProcessedFoodCandidateIndex(
    private val products: ProcessedFoodProductJpaRepository,
    @Value("\${kbap.processed-food.candidate-index.refresh-check:PT1M}") private val refreshCheck: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var snapshot: Snapshot? = null

    fun nameCandidateIds(keyword: String, seenSince: LocalDateTime, limit: Int): List<Long> {
        val key = keyword.lowercase()
        return fresh().entries.asSequence()
            .filter { it.isAvailableSince(seenSince) && key in it.name }
            .sortedWith(compareBy<Entry>({ it.name != key }, { !it.name.startsWith(key) }, { it.nameLength }, { it.id }))
            .take(limit)
            .map { it.id }
            .toList()
    }

    fun makerCandidateIds(keyword: String, seenSince: LocalDateTime, limit: Int): List<Long> {
        val key = keyword.lowercase()
        return fresh().entries.asSequence()
            .filter { it.isAvailableSince(seenSince) && it.makers.any { maker -> key in maker } }
            .sortedWith(compareBy<Entry>({ it.nameLength }, { it.id }))
            .take(limit)
            .map { it.id }
            .toList()
    }

    private fun fresh(): Snapshot {
        val current = snapshot
        val now = System.nanoTime()
        if (current != null && now - current.checkedAt < refreshCheck.toNanos()) return current
        synchronized(this) {
            val latest = snapshot
            if (latest != null && System.nanoTime() - latest.checkedAt < refreshCheck.toNanos()) return latest
            val next = try {
                val fingerprint = products.findCandidateFingerprint()
                if (latest != null && latest.fingerprint == fingerprint) latest.copy(checkedAt = System.nanoTime()) else load(fingerprint)
            } catch (e: Exception) {
                if (latest == null) throw e
                log.warn("가공식품 후보 색인 갱신 실패 — 기존 색인으로 답하고 다음 확인 때 다시 시도 rows={}", latest.entries.size, e)
                latest.copy(checkedAt = System.nanoTime())
            }
            snapshot = next
            return next
        }
    }

    private fun load(fingerprint: String?): Snapshot {
        val entries = products.findCandidateRows().map(::entryOf)
        log.info("가공식품 후보 색인 적재 rows={} chars={}", entries.size, entries.sumOf { it.name.length + it.makers.sumOf(String::length) })
        return Snapshot(entries, fingerprint, System.nanoTime())
    }

    private fun entryOf(row: ProcessedFoodCandidateRow): Entry {
        val raw = listOfNotNull(row.manufacturer, row.seller, row.makerSupplement).map { it.lowercase() }
        return Entry(
            id = row.id,
            name = row.normalizedName.lowercase(),
            nameLength = row.normalizedName.codePointCount(0, row.normalizedName.length),
            makers = (raw + raw.map { it.replace(MAKER_NOISE, "") }).distinct(),
            lastSeenAt = row.lastSeenAt,
            tracksDiscontinuation = ProcessedFoodProduct.tracksDiscontinuation(row.source),
        )
    }

    private data class Entry(
        val id: Long,
        val name: String,
        val nameLength: Int,
        val makers: List<String>,
        val lastSeenAt: LocalDateTime,
        val tracksDiscontinuation: Boolean,
    ) {
        fun isAvailableSince(seenSince: LocalDateTime): Boolean = !tracksDiscontinuation || !lastSeenAt.isBefore(seenSince)
    }

    private data class Snapshot(val entries: List<Entry>, val fingerprint: String?, val checkedAt: Long)

    private companion object {
        val MAKER_NOISE = Regex("""[ ()\[\]·・,._-]""")
    }
}
