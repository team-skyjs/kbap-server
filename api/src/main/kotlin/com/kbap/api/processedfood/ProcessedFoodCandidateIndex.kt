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

    fun nameCandidateIds(keyword: String, seenSince: LocalDateTime, limit: Int, type: ProcessedFoodType? = null): List<Long> {
        val key = keyword.lowercase()
        return fresh().entries.asSequence()
            .filter { it.isAvailableSince(seenSince) && (type == null || it.type == type) && key in it.name }
            .sortedWith(compareBy<Entry>({ it.name != key }, { !it.name.startsWith(key) }, { it.nameLength }, { it.id }))
            .take(limit)
            .map { it.id }
            .toList()
    }

    fun typoCandidates(token: String, context: List<String>, seenSince: LocalDateTime, limit: Int): TypoCandidates {
        val key = token.lowercase()
        if (key.length < MIN_TYPO_LENGTH || !key.all(::isHangulSyllable)) return TypoCandidates.NONE
        val contextKeys = context.map { it.lowercase() }.filter { it != key && it.length >= MIN_CONTEXT_LENGTH }
        if (contextKeys.isEmpty()) return TypoCandidates.NONE
        val hits = fresh().entries.asSequence()
            .filter { it.isAvailableSince(seenSince) && contextKeys.any { c -> c in it.name || it.makers.any { m -> m.length >= MIN_CONTEXT_LENGTH && (c in m || m in c) } } }
            .mapNotNull { entry -> oneSyllableOff(entry.name, key)?.let { entry to it } }
            .sortedWith(compareBy({ it.first.nameLength }, { it.first.id }))
            .take(limit)
            .toList()
        return TypoCandidates(hits.map { it.first.id }, hits.map { it.second }.toSet())
    }

    private fun oneSyllableOff(name: String, key: String): String? =
        (0..name.length - key.length).asSequence()
            .map { name.substring(it, it + key.length) }
            .firstOrNull { window -> window.indices.count { window[it] != key[it] } == 1 && window.all(::isHangulSyllable) }

    private fun isHangulSyllable(c: Char): Boolean = c in '가'..'힣'

    fun makerCandidateIds(keyword: String, seenSince: LocalDateTime, limit: Int, type: ProcessedFoodType? = null): List<Long> {
        val key = keyword.lowercase()
        return fresh().entries.asSequence()
            .filter { it.isAvailableSince(seenSince) && (type == null || it.type == type) && it.makers.any { maker -> key in maker } }
            .sortedWith(compareBy<Entry>({ it.nameLength }, { it.id }))
            .take(limit)
            .map { it.id }
            .toList()
    }

    fun listedRows(seenSince: LocalDateTime, type: ProcessedFoodType?): List<ListedRow> =
        fresh().entries.asSequence()
            .filter { it.isAvailableSince(seenSince) && (type == null || it.type == type) }
            .groupBy { it.groupKey }
            .values
            .map { group -> group.maxWith(compareBy<Entry>({ it.lastSeenAt }, { it.id })) }
            .sortedBy { it.id }
            .map { ListedRow(it.id, it.normalizedName, it.displayMaker, it.groupKey) }

    fun groupMemberIds(id: Long): List<Long> = groupMemberIds(listOf(id)).getValue(id)

    fun groupMemberIds(ids: Collection<Long>): Map<Long, List<Long>> {
        val membersById = fresh().membersById
        return ids.associateWith { membersById[it] ?: listOf(it) }
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
        val membersById = entries.groupBy({ it.groupKey }, { it.id }).values.flatMap { ids -> ids.map { it to ids } }.toMap()
        return Snapshot(entries, membersById, fingerprint, System.nanoTime())
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
            normalizedName = row.normalizedName,
            displayMaker = ProcessedFoodProduct.displayMakerOf(row.manufacturer, row.seller, row.makerSupplement),
            type = ProcessedFoodType.of(row.foodType),
        ).let { it.copy(groupKey = ProcessedFoodGrouping.keyOf(it.normalizedName, it.displayMaker)) }
    }

    private data class Entry(
        val id: Long,
        val name: String,
        val nameLength: Int,
        val makers: List<String>,
        val lastSeenAt: LocalDateTime,
        val tracksDiscontinuation: Boolean,
        val normalizedName: String,
        val displayMaker: String?,
        val type: ProcessedFoodType?,
        val groupKey: String = "",
    ) {
        fun isAvailableSince(seenSince: LocalDateTime): Boolean = !tracksDiscontinuation || !lastSeenAt.isBefore(seenSince)
    }

    data class TypoCandidates(val ids: List<Long>, val corrections: Set<String>) {
        companion object {
            val NONE = TypoCandidates(emptyList(), emptySet())
        }
    }

    data class ListedRow(val id: Long, val normalizedName: String, val displayMaker: String?, val groupKey: String)

    private data class Snapshot(val entries: List<Entry>, val membersById: Map<Long, List<Long>>, val fingerprint: String?, val checkedAt: Long)

    private companion object {
        val MAKER_NOISE = Regex("""[ ()\[\]·・,._-]""")
        const val MIN_TYPO_LENGTH = 3
        const val MIN_CONTEXT_LENGTH = 2
    }
}
