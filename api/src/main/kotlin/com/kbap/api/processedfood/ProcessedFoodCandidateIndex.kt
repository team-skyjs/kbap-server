package com.kbap.api.processedfood

import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.domain.processedfood.dto.ProcessedFoodCandidateRow
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import com.kbap.common.domain.translation.ContentTranslationJpaRepository
import com.kbap.common.domain.translation.ProcessedFoodNameTranslationRow
import com.kbap.common.domain.translation.model.TranslationTargetType
import com.kbap.common.port.llm.ProcessedFoodTextTranslator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

@Component
class ProcessedFoodCandidateIndex(
    private val products: ProcessedFoodProductJpaRepository,
    private val translations: ContentTranslationJpaRepository,
    private val translator: ProcessedFoodTextTranslator?,
    @Value("\${kbap.processed-food.candidate-index.refresh-check:PT1M}") private val refreshCheck: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var snapshot: Snapshot? = null

    fun nameCandidateIds(keyword: String, seenSince: LocalDateTime, limit: Int, type: ProcessedFoodType? = null): List<Long> {
        val key = keyword.lowercase()
        return fresh().entries.asSequence()
            .filter { it.isAvailableSince(seenSince) && (type == null || it.type == type) }
            .mapNotNull { entry -> matchOf(entry, key)?.let { entry to it } }
            .sortedWith(compareBy<Pair<Entry, NameMatch>>({ it.second.grade }, { it.second.source }, { it.second.length }, { it.first.id }))
            .take(limit)
            .map { it.first.id }
            .toList()
    }

    fun translatedNamesOf(ids: Collection<Long>): Map<Long, List<TranslatedName>> {
        val byId = fresh().translatedById
        return ids.associateWith { byId[it].orEmpty() }
    }

    private fun matchOf(entry: Entry, key: String): NameMatch? =
        (listOf(NameMatch.of(entry.name, key, ORIGINAL, entry.nameLength)) + entry.translated.map { NameMatch.of(it.name, key, it.source, it.name.length) })
            .filterNotNull()
            .minWithOrNull(compareBy({ it.grade }, { it.source }, { it.length }))

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
                val fingerprint = products.findCandidateFingerprint() + "#" + translations.findProcessedFoodNameFingerprint()
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

    fun groupKeyOf(id: Long): String? = fresh().groupKeyById[id]

    private fun load(fingerprint: String?): Snapshot {
        val translationRows = translations.findProcessedFoodNameRows().groupBy { it.targetId }
        val entries = foldUnknownMakers(products.findCandidateRows().map { entryOf(it, translationRows[it.id].orEmpty()) })
        log.info(
            "가공식품 후보 색인 적재 rows={} translatedNames={} chars={}",
            entries.size,
            entries.sumOf { it.translated.size },
            entries.sumOf { it.name.length + it.makers.sumOf(String::length) + it.translated.sumOf { t -> t.name.length } },
        )
        val membersById = entries.groupBy({ it.groupKey }, { it.id }).values.flatMap { ids -> ids.map { it to ids } }.toMap()
        val translatedById = entries.filter { it.translated.isNotEmpty() }.associate { it.id to it.translated }
        return Snapshot(entries, membersById, entries.associate { it.id to it.groupKey }, translatedById, fingerprint, System.nanoTime())
    }

    private fun foldUnknownMakers(entries: List<Entry>): List<Entry> =
        entries.zip(ProcessedFoodGrouping.foldUnknownMakers(entries.map { ProcessedFoodGrouping.GroupRow(it.normalizedName, it.displayMaker, it.groupKey) }))
            .map { (entry, key) -> if (key == entry.groupKey) entry else entry.copy(groupKey = key) }

    private fun translatedNamesOf(row: ProcessedFoodCandidateRow, displayMaker: String?, rows: List<ProcessedFoodNameTranslationRow>): List<TranslatedName> {
        if (rows.isEmpty()) return emptyList()
        val hash by lazy { ProcessedFoodNameSource.hashOf(ProcessedFoodNameSource.of(row.productName, displayMaker, translator)) }
        return rows.groupBy { it.language }.values.mapNotNull { byLanguage ->
            byLanguage.firstOrNull { it.targetType == TranslationTargetType.PF_NAME_PINNED.name }?.let { it.translatedText to PINNED }
                ?: byLanguage.firstOrNull { it.targetType == TranslationTargetType.PF_NAME.name && it.sourceHash == hash }?.let { it.translatedText to AUTO }
        }
            .map { (text, source) -> TranslatedName(ProcessedFoodSnapshot.normalizeName(text).lowercase(), source) }
            .filter { it.name.isNotEmpty() && it.name != row.normalizedName.lowercase() }
            .sortedBy { it.source }
            .distinctBy { it.name }
    }

    private fun entryOf(row: ProcessedFoodCandidateRow, translationRows: List<ProcessedFoodNameTranslationRow>): Entry {
        val raw = listOfNotNull(row.manufacturer, row.seller, row.makerSupplement).map { it.lowercase() }
        val displayMaker = ProcessedFoodProduct.displayMakerOf(row.manufacturer, row.seller, row.makerSupplement)
        return Entry(
            id = row.id,
            name = row.normalizedName.lowercase(),
            nameLength = row.normalizedName.codePointCount(0, row.normalizedName.length),
            makers = (raw + raw.map { it.replace(MAKER_NOISE, "") }).distinct(),
            lastSeenAt = row.lastSeenAt,
            tracksDiscontinuation = ProcessedFoodProduct.tracksDiscontinuation(row.source),
            normalizedName = row.normalizedName,
            displayMaker = displayMaker,
            type = ProcessedFoodType.of(row.foodType, row.normalizedName),
            translated = translatedNamesOf(row, displayMaker, translationRows),
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
        val translated: List<TranslatedName>,
        val groupKey: String = "",
    ) {
        fun isAvailableSince(seenSince: LocalDateTime): Boolean = !tracksDiscontinuation || !lastSeenAt.isBefore(seenSince)
    }

    data class TypoCandidates(val ids: List<Long>, val corrections: Set<String>) {
        companion object {
            val NONE = TypoCandidates(emptyList(), emptySet())
        }
    }

    data class TranslatedName(val name: String, val source: Int)

    private data class NameMatch(val grade: Int, val source: Int, val length: Int) {
        companion object {
            fun of(name: String, key: String, source: Int, length: Int): NameMatch? = when {
                name == key -> NameMatch(0, source, length)
                name.startsWith(key) -> NameMatch(1, source, length)
                key in name -> NameMatch(2, source, length)
                else -> null
            }
        }
    }

    data class ListedRow(val id: Long, val normalizedName: String, val displayMaker: String?, val groupKey: String)

    private data class Snapshot(
        val entries: List<Entry>,
        val membersById: Map<Long, List<Long>>,
        val groupKeyById: Map<Long, String>,
        val translatedById: Map<Long, List<TranslatedName>>,
        val fingerprint: String?,
        val checkedAt: Long,
    )

    companion object {
        const val ORIGINAL = 0
        const val PINNED = 1
        const val AUTO = 2
        private val MAKER_NOISE = Regex("""[ ()\[\]·・,._-]""")
        private const val MIN_TYPO_LENGTH = 3
        private const val MIN_CONTEXT_LENGTH = 2
    }
}
