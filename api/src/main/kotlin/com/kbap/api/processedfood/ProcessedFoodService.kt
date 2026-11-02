package com.kbap.api.processedfood

import com.kbap.api.core.SearchPage
import com.kbap.api.member.MemberService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.ingredient.model.Avoidance
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.domain.processedfood.model.CutoutReviewStatus
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import com.kbap.common.domain.review.ProcessedFoodRatingAggregate
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.util.RamyeonSpelling
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

@Service
class ProcessedFoodService(
    private val products: ProcessedFoodProductJpaRepository,
    private val memberService: MemberService,
    private val reviews: ReviewJpaRepository,
    private val candidateIndex: ProcessedFoodCandidateIndex,
    private val featuredCatalog: ProcessedFoodFeaturedCatalog,
    private val cvsCatalog: ProcessedFoodCvsCatalog,
    private val popularity: ProcessedFoodPopularity,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun searchProcessedFoodPage(
        query: String?,
        cursor: String?,
        size: Int,
        memberId: Long? = null,
        type: ProcessedFoodType? = null,
        verdicts: Set<ProcessedFoodVerdict> = emptySet(),
    ): SearchPage<ProcessedFoodSummaryResponse> {
        val tokens = ProcessedFoodSearchRanker.tokensOf(query.orEmpty())
        val offset = cursor?.let { it.toIntOrNull()?.takeIf { value -> value >= 0 && (tokens.isEmpty() || value <= MAX_OFFSET) } ?: throw BusinessException(ErrorCode.INVALID_CURSOR) } ?: 0
        val lastMirror = products.findLastCompletedMirrorStartedAt()
        val filter = verdicts.takeIf { it.isNotEmpty() }?.let { memberId }?.let { memberService.getAvoidance(it) }?.takeIf { it.chosen.isNotEmpty() }
            ?.let { VerdictFilter(it, verdicts) }
        if (tokens.isEmpty()) return listPage(type, offset, size, lastMirror, memberId, filter)
        val nameTokens = tokens.map(RamyeonSpelling::koreanTokenOf).distinct()
        val candidates = candidatesFor(nameTokens, (tokens + ProcessedFoodSearchRanker.rawTokensOf(query.orEmpty())).distinct(), lastMirror, type)
        val ranked = featuredFirst(ProcessedFoodSearchRanker.scored(candidates, nameTokens).sortedWith(ProcessedFoodSearchRanker.ORDER), tokens.joinToString(" "), tokens, lastMirror, type, keepGroupMembers = true).products
            .filter { type == null || ProcessedFoodType.of(it.foodType, it.normalizedName) == type }
            .groupBy(::groupKeyOf).values.map { group -> group.sortedWith(representativeOrder) }
        val rankedById = ranked.flatten().associateBy { it.id }
        return pageOf(ranked.map { group -> group.map { it.id } }, offset, size, memberId, filter) { ids -> ids.mapNotNull(rankedById::get).associateBy { it.id } }
    }

    private fun listPage(type: ProcessedFoodType?, offset: Int, size: Int, lastMirror: LocalDateTime?, memberId: Long?, filter: VerdictFilter?): SearchPage<ProcessedFoodSummaryResponse> {
        cvsListGroups(type, lastMirror)?.let { groups ->
            return pageOf(groups, offset, size, memberId, filter) { pageIds -> products.findAllById(pageIds).associateBy { it.id } }
        }
        val catalog = featuredCatalog.productsByPriority()
        val named = products.findAllByNormalizedNameIn(catalog.flatMap(::namesOf)).filterNot { it.isDiscontinued(lastMirror) }
            .filter { type == null || ProcessedFoodType.of(it.foodType, it.normalizedName) == type }
        val featured = catalog.mapNotNull { product -> representativeOf(product, named)?.let { it to product } }
            .distinctBy { groupKeyOf(it.first) }
        val featuredIds = featured.map { it.first.id }.toSet()
        val featuredGroups = featured.map { (row, _) -> groupKeyOf(row) }.toSet()
        val featuredByName = featured.flatMap { (_, product) -> namesOf(product).map { it.lowercase() to product } }.groupBy({ it.first }, { it.second })
        val makerPrefixes = featured.flatMap { (_, product) -> featuredCatalog.makerAliases(product.maker) }.distinct()
        val rest = candidateIndex.listedRows(lastMirror?.minus(ProcessedFoodProduct.DISCONTINUED_AFTER) ?: NO_BASELINE, type)
            .filterNot { row ->
                row.id in featuredIds || row.groupKey in featuredGroups || foldsIntoFeatured(row, featuredByName, makerPrefixes)
            }
            .map { it.id }
        val ids = featured.map { it.first.id } + rest
        return pageOf(ids.map(::listOf), offset, size, memberId, filter) { pageIds -> products.findAllById(pageIds).associateBy { it.id } }
    }

    private fun cvsListGroups(type: ProcessedFoodType?, lastMirror: LocalDateTime?): List<List<Long>>? {
        if (cvsCatalog.reportNos.isEmpty()) return null
        val stocked = products.findAllByReportNoIn(cvsCatalog.reportNos).filterNot { it.isDiscontinued(lastMirror) }
        if (stocked.isEmpty()) return null
        val byReportNo = stocked.filter { type == null || ProcessedFoodType.of(it.foodType, it.normalizedName) == type }.associateBy { it.reportNo }
        val ranked = seedLineRepresentativesOf(byReportNo)
        val used = ranked.map { it.second.id }.toSet()
        val representatives = ranked.map { it.second }
        val representedGroups = representatives.map(::groupKeyOf).toSet()
        val restGroups = byReportNo.values
            .filter { it.id !in used && groupKeyOf(it) !in representedGroups }
            .groupBy(::groupKeyOf).values
            .map { group -> group.sortedWith(representativeOrder) }
            .sortedBy { it.first().id }
            .shuffled(Random(LocalDate.now(SEOUL).toEpochDay()))
        val seedGroups = representatives.map { lead ->
            listOf(lead) + byReportNo.values.filter { it.id != lead.id && it.id !in used && groupKeyOf(it) == groupKeyOf(lead) }.sortedWith(representativeOrder)
        }
        val membersByLead = (seedGroups + restGroups).associate { group -> group.first().id to group.map { it.id } }
        return popularity.order(
            ranked.map { (rank, row) -> ProcessedFoodPopularity.Entry(row.id, groupKeyOf(row), popularity.seedPercentileOf(rank)) } +
                restGroups.map { ProcessedFoodPopularity.Entry(it.first().id, groupKeyOf(it.first()), ProcessedFoodPopularity.NON_SEED_PERCENTILE) },
        ).map { id -> membersByLead[id] ?: listOf(id) }
    }

    @Transactional(readOnly = true)
    fun getSeedLineRepresentatives(): List<Pair<Int, ProcessedFoodProduct>> {
        if (cvsCatalog.reportNos.isEmpty()) return emptyList()
        val lastMirror = products.findLastCompletedMirrorStartedAt()
        val byReportNo = products.findAllByReportNoIn(cvsCatalog.reportNos).filterNot { it.isDiscontinued(lastMirror) }.associateBy { it.reportNo }
        return seedLineRepresentativesOf(byReportNo)
    }

    private fun seedLineRepresentativesOf(byReportNo: Map<String, ProcessedFoodProduct>): List<Pair<Int, ProcessedFoodProduct>> {
        val used = mutableSetOf<Long>()
        return cvsCatalog.seedLines.mapNotNull { line ->
            val pool = line.reportNos.mapNotNull(byReportNo::get).filter { it.id !in used }
            (line.representative?.let(byReportNo::get)?.takeIf { it.id !in used } ?: lineRepresentativeOf(line.line, pool))
                ?.also { used += it.id }?.let { line.rank to it }
        }
    }

    private fun lineRepresentativeOf(line: String, pool: List<ProcessedFoodProduct>): ProcessedFoodProduct? {
        val lineKey = ProcessedFoodSnapshot.normalizeName(line).lowercase()
        fun key(row: ProcessedFoodProduct) = row.normalizedName.lowercase()
        val candidates = pool.filter { key(it) == lineKey }.ifEmpty { pool.filter { lineKey in key(it) } }.ifEmpty { pool }
        return candidates.minWithOrNull(compareBy<ProcessedFoodProduct> { key(it).length }.then(representativeOrder))
    }

    private val seedRankByReportNo: Map<String, Int> by lazy {
        cvsCatalog.seedLines.flatMap { line -> line.reportNos.map { it to line.rank } }.groupBy({ it.first }, { it.second }).mapValues { (_, ranks) -> ranks.min() }
    }

    private val representativeOrder: Comparator<ProcessedFoodProduct> = compareBy<ProcessedFoodProduct>(
        { it.cutoutReviewStatus != CutoutReviewStatus.APPROVED },
        { seedRankByReportNo[it.reportNo] ?: Int.MAX_VALUE },
        { -(it.cutoutCoverage?.toDouble() ?: NO_COVERAGE) },
    ).thenByDescending { it.lastSeenAt }.thenBy { it.id }

    private fun groupKeyOf(row: ProcessedFoodProduct): String = candidateIndex.groupKeyOf(row.id) ?: ProcessedFoodGrouping.keyOf(row.normalizedName, row.displayMaker())

    private fun foldsIntoFeatured(
        row: ProcessedFoodCandidateIndex.ListedRow,
        featuredByName: Map<String, List<ProcessedFoodFeaturedCatalog.FeaturedProduct>>,
        makerPrefixes: List<String>,
    ): Boolean {
        val key = row.normalizedName.lowercase()
        val names = listOf(key) + makerPrefixes.filter { key.startsWith(it) && key.length > it.length }.map { key.removePrefix(it) }
        return names.any { name -> featuredByName[name]?.any { foldsInto(row.normalizedName, row.displayMaker, it) } == true }
    }

    @Transactional(readOnly = true)
    fun getSummaries(products: List<ProcessedFoodProduct>, memberId: Long?): List<ProcessedFoodSummaryResponse> {
        val members = candidateIndex.groupMemberIds(products.map { it.id })
        val ratings = reviews.aggregateRatingsByProcessedFoodIds(members.values.flatten().distinct()).associateBy { it.processedFoodId }
        val avoidance = memberId?.let { memberService.getAvoidance(it) }
        return products.map { ProcessedFoodSummaryResponse.withReviews(it, GroupRating.of(it.id, members.getValue(it.id).mapNotNull(ratings::get)), avoidance) }
    }

    private fun pageOf(
        groups: List<List<Long>>,
        offset: Int,
        size: Int,
        memberId: Long?,
        filter: VerdictFilter?,
        load: (List<Long>) -> Map<Long, ProcessedFoodProduct>,
    ): SearchPage<ProcessedFoodSummaryResponse> {
        if (filter == null) {
            val pageIds = groups.drop(offset).take(size).map { it.first() }
            val loaded = load(pageIds)
            return summaryPage(pageIds.mapNotNull(loaded::get), groups.size > offset.toLong() + size, offset.toLong() + size, memberId)
        }
        val matched = mutableListOf<ProcessedFoodProduct>()
        var position = offset
        val scanEnd = minOf(groups.size, offset + MAX_VERDICT_SCAN)
        while (position < scanEnd && matched.size < size) {
            val chunk = groups.subList(position, minOf(scanEnd, position + VERDICT_SCAN_CHUNK))
            val loaded = load(chunk.flatten())
            for (group in chunk) {
                position++
                group.firstNotNullOfOrNull { id -> loaded[id]?.takeIf(filter::matches) }?.let { matched += it }
                if (matched.size == size) break
            }
        }
        return summaryPage(matched, position < groups.size, position.toLong(), memberId)
    }

    private fun summaryPage(page: List<ProcessedFoodProduct>, hasNext: Boolean, next: Long, memberId: Long?): SearchPage<ProcessedFoodSummaryResponse> =
        SearchPage(items = getSummaries(page, memberId), hasNext = hasNext, nextCursor = if (hasNext) next.toString() else null)

    private class VerdictFilter(private val avoidance: Avoidance, private val verdicts: Set<ProcessedFoodVerdict>) {
        fun matches(product: ProcessedFoodProduct): Boolean =
            ProcessedFoodJudge.judge(product.allergens, product.rawMaterials, avoidance).verdict in verdicts
    }

    @Transactional(readOnly = true)
    fun getProcessedFoodDetail(id: Long, memberId: Long?): ProcessedFoodDetailResponse =
        detailOf(available(products.findById(id).orElse(null)), memberId)

    @Transactional(readOnly = true)
    fun getAvailableProduct(id: Long): ProcessedFoodProduct = available(products.findById(id).orElse(null))

    @Transactional(readOnly = true)
    fun getProcessedFoodDetailByReportNo(reportNo: String, memberId: Long?): ProcessedFoodDetailResponse =
        detailOf(available(products.findByReportNo(reportNo)), memberId)

    @Transactional(readOnly = true)
    fun getConfirmedProcessedFoodDetail(reportNo: String, memberId: Long?): ProcessedFoodDetailResponse =
        detailOf(products.findByReportNo(reportNo) ?: throw BusinessException(ErrorCode.PROCESSED_FOOD_NOT_FOUND), memberId)

    @Transactional(readOnly = true)
    fun getNameScanCandidates(queries: List<String>, limit: Int): RankedCandidates {
        val tokensByQuery = queries.map(ProcessedFoodSearchRanker::tokensOf).filter { it.isNotEmpty() }
        if (tokensByQuery.isEmpty()) return RankedCandidates(emptyList(), emptyList())
        val tokens = tokensByQuery.flatten().distinct().take(MAX_SCAN_TOKENS)
        val rawTokens = queries.flatMap(ProcessedFoodSearchRanker::rawTokensOf).distinct().take(MAX_SCAN_TOKENS)
        val lastMirror = products.findLastCompletedMirrorStartedAt()
        val seenSince = seenSinceOf(lastMirror)
        val typos = tokens.filter { candidateIndex.nameCandidateIds(it, seenSince, 1).isEmpty() }
            .associateWith { candidateIndex.typoCandidates(it, (tokens + rawTokens).filterNot { other -> other.equals(it, ignoreCase = true) }, seenSince, CANDIDATE_LIMIT) }
            .filterValues { it.ids.isNotEmpty() }
        val corrections = typos.values.flatMap { it.corrections }.toSet()
        val candidates = (candidatesFor(tokens, (tokens + rawTokens).distinct(), lastMirror) + products.findAllById(typos.values.flatMap { it.ids }.distinct()))
            .distinctBy { it.id }
        val best = linkedMapOf<Long, Pair<ProcessedFoodProduct, ProcessedFoodSearchScore>>()
        tokensByQuery.map { queryTokens ->
            queryTokens + queryTokens.flatMap { typos[it]?.corrections.orEmpty() }.distinct().filterNot { correction -> queryTokens.any { correction in it } }
        }.forEach { queryTokens ->
            ProcessedFoodSearchRanker.scored(candidates, queryTokens, corrections).forEach { scored ->
                best.merge(scored.first.id, scored) { kept, next -> if (ProcessedFoodSearchRanker.ORDER.compare(kept, next) <= 0) kept else next }
            }
        }
        val gatedIds = typos.values.flatMap { it.ids }.toSet()
        val correctedBoost = CorrectedBoost((queries + corrections).joinToString(" "), tokens + corrections) { representative ->
            candidateIndex.groupMemberIds(representative.id).any { it in gatedIds }
        }.takeIf { corrections.isNotEmpty() }
        val ranked = featuredFirst(best.values.sortedWith(ProcessedFoodSearchRanker.ORDER), queries.joinToString(" "), tokens, lastMirror, correctedBoost = correctedBoost)
        return ranked.copy(products = ranked.products.distinctBy(::groupKeyOf).take(limit))
    }

    private fun featuredFirst(
        ranked: List<Pair<ProcessedFoodProduct, ProcessedFoodSearchScore>>,
        text: String,
        tokens: List<String>,
        lastMirror: LocalDateTime?,
        type: ProcessedFoodType? = null,
        correctedBoost: CorrectedBoost? = null,
        keepGroupMembers: Boolean = false,
    ): RankedCandidates {
        val plain = featuredCatalog.boostedProducts(text, tokens)
        val corrected = correctedBoost?.let { featuredCatalog.boostedProducts(it.text, it.tokens) }.orEmpty()
            .filterNot { boost -> plain.any { it.product == boost.product } }
        val featured = plain + corrected
        val featuredRows = if (featured.isEmpty()) {
            emptyList()
        } else {
            products.findAllByNormalizedNameIn(featured.flatMap { namesOf(it.product) }).filterNot { it.isDiscontinued(lastMirror) }
                .filter { type == null || ProcessedFoodType.of(it.foodType, it.normalizedName) == type }
        }
        val boosted = if (featured.isEmpty()) {
            emptyList()
        } else {
            featured.mapNotNull { boost ->
                representativeOf(boost.product, featuredRows, if (keepGroupMembers) ::byRepresentativeRule else ::latestOf)?.let { Triple(it, boost.matchLength, boost.product) }
                    ?: null.also { log.warn("가공식품 대표 상품이 DB 에 없어 건너뜁니다 name={} maker={}", boost.product.name, boost.product.maker) }
            }.filter { (representative, _, product) -> plain.any { it.product == product } || correctedBoost?.allows?.invoke(representative) == true }
        }
        val boostedIds = boosted.map { it.first.id }.toSet()
        val rest = ranked.filterNot { (product, _) ->
            product.id in boostedIds || boosted.any { (_, _, featuredProduct) -> foldsInto(product.normalizedName, product.displayMaker(), featuredProduct) }
        }
            .groupBy { groupKeyOf(it.first) }.values
            .map { group ->
                val members = group.map { it.first }
                (if (keepGroupMembers) members.sortedWith(representativeOrder) else listOf(latestOf(members)!!)) to group.first().second
            }
        val longestBoost = boosted.maxOfOrNull { it.second } ?: 0
        val boostedNameKeys = boosted.flatMap { listOf(it.third.name, it.first.normalizedName) }.map { it.lowercase() }.toSet()
        val (exactAbove, below) = rest.partition {
            val lead = it.first.first()
            boosted.isNotEmpty() && ProcessedFoodSearchRanker.isExact(it.second) && lead.normalizedName.length >= longestBoost &&
                lead.normalizedName.lowercase() !in boostedNameKeys
        }
        val boostedRows = if (keepGroupMembers) {
            boosted.flatMap { (representative, _, _) ->
                listOf(representative) + featuredRows.filter { it.id != representative.id && groupKeyOf(it) == groupKeyOf(representative) }.sortedWith(representativeOrder)
            }
        } else {
            boosted.map { it.first }
        }
        return RankedCandidates(exactAbove.flatMap { it.first } + boostedRows + below.flatMap { it.first }, boosted.map { it.first.reportNo })
    }

    @Transactional(readOnly = true)
    fun getFeaturedProducts(): List<ProcessedFoodProduct> {
        val catalog = featuredCatalog.productsByPriority()
        val lastMirror = products.findLastCompletedMirrorStartedAt()
        val named = products.findAllByNormalizedNameIn(catalog.flatMap(::namesOf)).filterNot { it.isDiscontinued(lastMirror) }
        return catalog.mapNotNull { representativeOf(it, named) }.distinctBy { it.id }
    }

    private fun namesOf(product: ProcessedFoodFeaturedCatalog.FeaturedProduct): List<String> =
        (listOf(product.name) + product.aliases).map(ProcessedFoodSnapshot::normalizeName).distinct()

    private fun byRepresentativeRule(rows: List<ProcessedFoodProduct>): ProcessedFoodProduct? = rows.minWithOrNull(representativeOrder)

    private fun representativeOf(
        product: ProcessedFoodFeaturedCatalog.FeaturedProduct,
        rows: List<ProcessedFoodProduct>,
        pick: (List<ProcessedFoodProduct>) -> ProcessedFoodProduct? = ::latestOf,
    ): ProcessedFoodProduct? {
        val names = namesOf(product).map { it.lowercase() }.toSet()
        val named = rows.filter { it.normalizedName.lowercase() in names }
        val pool = named.filter { isMadeBy(it.displayMaker(), product.maker) }.ifEmpty { named.filter { it.normalizedName.equals(product.name, ignoreCase = true) } }
        return pick(pool.filter { it.normalizedName.equals(product.name, ignoreCase = true) }) ?: pick(pool)
    }

    private fun foldsInto(normalizedName: String, displayMaker: String?, product: ProcessedFoodFeaturedCatalog.FeaturedProduct): Boolean {
        if (!isMadeBy(displayMaker, product.maker) && displayMaker != null) return false
        val names = namesOf(product).map { it.lowercase() }.toSet()
        return normalizedName.lowercase() in names || withoutMaker(normalizedName, product.maker).any { it in names }
    }

    private fun isMadeBy(displayMaker: String?, maker: String): Boolean {
        val key = ProcessedFoodGrouping.makerKeyOf(displayMaker).lowercase()
        return key.isNotEmpty() && featuredCatalog.makerAliases(maker).any { it in key }
    }

    private fun withoutMaker(name: String, maker: String): List<String> {
        val key = name.lowercase()
        return featuredCatalog.makerAliases(maker).filter { key.startsWith(it) && key.length > it.length }.map { key.removePrefix(it) }
    }

    private fun latestOf(rows: List<ProcessedFoodProduct>): ProcessedFoodProduct? =
        rows.maxWithOrNull(compareBy<ProcessedFoodProduct>({ it.lastSeenAt }, { it.id }))


    private fun available(product: ProcessedFoodProduct?): ProcessedFoodProduct {
        if (product == null) throw BusinessException(ErrorCode.PROCESSED_FOOD_NOT_FOUND)
        if (product.isDiscontinued(products.findLastCompletedMirrorStartedAt())) {
            throw BusinessException(ErrorCode.PROCESSED_FOOD_DISCONTINUED)
        }
        return product
    }

    private fun detailOf(product: ProcessedFoodProduct, memberId: Long?): ProcessedFoodDetailResponse {
        val members = candidateIndex.groupMemberIds(product.id)
        val lastMirror = products.findLastCompletedMirrorStartedAt()
        val siblings = products.findAllById(members).filterNot { it.isDiscontinued(lastMirror) }.sortedBy { it.id }
            .map { ProcessedFoodSameNameResponse(it.id, it.capacity) }
        return ProcessedFoodDetailResponse.of(product, memberId?.let { memberService.getAvoidance(it) }, reviews.aggregateAxesByProcessedFoodIds(members), siblings)
    }

    private fun seenSinceOf(lastMirror: LocalDateTime?): LocalDateTime = lastMirror?.minus(ProcessedFoodProduct.DISCONTINUED_AFTER) ?: NO_BASELINE

    private fun candidatesFor(tokens: List<String>, makerKeywords: List<String>, lastMirror: LocalDateTime?, type: ProcessedFoodType? = null): List<ProcessedFoodProduct> {
        val seenSince = seenSinceOf(lastMirror)
        return (
            tokens.flatMap { candidateIndex.nameCandidateIds(it, seenSince, CANDIDATE_LIMIT, type) } +
                makerKeywords.flatMap { candidateIndex.makerCandidateIds(it, seenSince, CANDIDATE_LIMIT, type) }
            ).distinct().let(products::findAllById)
    }

    companion object {
        private const val CANDIDATE_LIMIT = 300
        private const val MAX_OFFSET = 10_000
        const val MAX_VERDICT_SCAN = 200
        private const val VERDICT_SCAN_CHUNK = 50
        private const val MAX_SCAN_TOKENS = 10
        private const val NO_COVERAGE = -1.0
        private val NO_BASELINE: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0)
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

private data class GroupRating(override val processedFoodId: Long, override val average: Double?, override val reviewCount: Long) : ProcessedFoodRatingAggregate {
    companion object {
        fun of(id: Long, members: List<ProcessedFoodRatingAggregate>): GroupRating? {
            val count = members.sumOf { it.reviewCount }
            if (count == 0L) return null
            return GroupRating(id, members.sumOf { (it.average ?: 0.0) * it.reviewCount } / count, count)
        }
    }
}

private class CorrectedBoost(val text: String, val tokens: List<String>, val allows: (ProcessedFoodProduct) -> Boolean)

data class RankedCandidates(val products: List<ProcessedFoodProduct>, val boostedReportNos: List<String>)
