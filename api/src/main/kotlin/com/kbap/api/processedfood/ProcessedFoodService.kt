package com.kbap.api.processedfood

import com.kbap.api.core.SearchPage
import com.kbap.api.member.MemberService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.ingredient.label.IngredientLabelReader
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import com.kbap.common.domain.review.ReviewJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class ProcessedFoodService(
    private val products: ProcessedFoodProductJpaRepository,
    private val memberService: MemberService,
    private val reviews: ReviewJpaRepository,
    private val candidateIndex: ProcessedFoodCandidateIndex,
    private val featuredCatalog: ProcessedFoodFeaturedCatalog,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun searchProcessedFoodPage(query: String?, cursor: String?, size: Int, memberId: Long? = null, type: ProcessedFoodType? = null): SearchPage<ProcessedFoodSummaryResponse> {
        val tokens = ProcessedFoodSearchRanker.tokensOf(query.orEmpty())
        val offset = cursor?.let { it.toIntOrNull()?.takeIf { value -> value in 0..MAX_OFFSET } ?: throw BusinessException(ErrorCode.INVALID_CURSOR) } ?: 0
        val lastMirror = products.findLastCompletedMirrorStartedAt()
        if (tokens.isEmpty()) return listPage(type, offset, size, lastMirror, memberId)
        val candidates = candidatesFor(tokens, (tokens + ProcessedFoodSearchRanker.rawTokensOf(query.orEmpty())).distinct(), lastMirror)
        val ranked = featuredFirst(ProcessedFoodSearchRanker.scored(candidates, tokens).sortedWith(ProcessedFoodSearchRanker.ORDER), tokens.joinToString(" "), tokens, lastMirror).products
            .filter { type == null || ProcessedFoodType.of(it.foodType) == type }
        return pageOf(ranked.drop(offset).take(size), ranked.size > offset + size, offset, size, memberId)
    }

    private fun listPage(type: ProcessedFoodType?, offset: Int, size: Int, lastMirror: LocalDateTime?, memberId: Long?): SearchPage<ProcessedFoodSummaryResponse> {
        val catalog = featuredCatalog.productsByPriority()
        val named = products.findAllByNormalizedNameIn(catalog.flatMap(::namesOf)).filterNot { it.isDiscontinued(lastMirror) }
        val featured = catalog.mapNotNull { product -> representativeOf(product, named)?.let { it to product } }
            .filter { (row, _) -> type == null || ProcessedFoodType.of(row.foodType) == type }
            .distinctBy { it.first.id }
        val featuredIds = featured.map { it.first.id }.toSet()
        val featuredGroups = featured.map { (row, _) -> ProcessedFoodGrouping.keyOf(row.normalizedName, row.displayMaker()) }.toSet()
        val rest = candidateIndex.listedRows(lastMirror?.minus(ProcessedFoodProduct.DISCONTINUED_AFTER) ?: NO_BASELINE, type)
            .filterNot { row ->
                row.id in featuredIds || ProcessedFoodGrouping.keyOf(row.normalizedName, row.displayMaker) in featuredGroups ||
                    featured.any { (_, product) -> foldsInto(row.normalizedName, row.displayMaker, product) }
            }
            .map { it.id }
        val ids = featured.map { it.first.id } + rest
        val pageIds = ids.drop(offset).take(size)
        val loaded = products.findAllById(pageIds).associateBy { it.id }
        return pageOf(pageIds.mapNotNull(loaded::get), ids.size > offset + size, offset, size, memberId)
    }

    private fun pageOf(page: List<ProcessedFoodProduct>, hasNext: Boolean, offset: Int, size: Int, memberId: Long?): SearchPage<ProcessedFoodSummaryResponse> {
        val ratings = reviews.aggregateRatingsByProcessedFoodIds(page.map { it.id }).associateBy { it.processedFoodId }
        val avoidance = memberId?.let { memberService.getAvoidance(it) }
        return SearchPage(
            items = page.map { ProcessedFoodSummaryResponse.withReviews(it, ratings[it.id], avoidance) },
            hasNext = hasNext,
            nextCursor = if (hasNext) (offset + size).toString() else null,
        )
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
        val candidates = candidatesFor(tokens, (tokens + rawTokens).distinct(), lastMirror)
        val best = linkedMapOf<Long, Pair<ProcessedFoodProduct, ProcessedFoodSearchScore>>()
        tokensByQuery.forEach { queryTokens ->
            ProcessedFoodSearchRanker.scored(candidates, queryTokens).forEach { scored ->
                best.merge(scored.first.id, scored) { kept, next -> if (ProcessedFoodSearchRanker.ORDER.compare(kept, next) <= 0) kept else next }
            }
        }
        val ranked = featuredFirst(best.values.sortedWith(ProcessedFoodSearchRanker.ORDER), queries.joinToString(" "), tokens, lastMirror)
        return ranked.copy(products = ranked.products.take(limit))
    }

    private fun featuredFirst(ranked: List<Pair<ProcessedFoodProduct, ProcessedFoodSearchScore>>, text: String, tokens: List<String>, lastMirror: LocalDateTime?): RankedCandidates {
        val featured = featuredCatalog.boostedProducts(text, tokens)
        val boosted = if (featured.isEmpty()) {
            emptyList()
        } else {
            val rows = products.findAllByNormalizedNameIn(featured.flatMap { namesOf(it.product) }).filterNot { it.isDiscontinued(lastMirror) }
            featured.mapNotNull { boost ->
                representativeOf(boost.product, rows)?.let { Triple(it, boost.matchLength, boost.product) }
                    ?: null.also { log.warn("가공식품 대표 상품이 DB 에 없어 건너뜁니다 name={} maker={}", boost.product.name, boost.product.maker) }
            }
        }
        val boostedIds = boosted.map { it.first.id }.toSet()
        val rest = ranked.filterNot { (product, _) ->
            product.id in boostedIds || boosted.any { (_, _, featuredProduct) -> foldsInto(product.normalizedName, product.displayMaker(), featuredProduct) }
        }
            .groupBy { ProcessedFoodGrouping.keyOf(it.first.normalizedName, it.first.displayMaker()) }.values
            .map { group -> latestOf(group.map { it.first })!! to group.first().second }
        val longestBoost = boosted.maxOfOrNull { it.second } ?: 0
        val boostedNameKeys = boosted.flatMap { listOf(it.third.name, it.first.normalizedName) }.map { it.lowercase() }.toSet()
        val (exactAbove, below) = rest.partition {
            boosted.isNotEmpty() && ProcessedFoodSearchRanker.isExact(it.second) && it.first.normalizedName.length >= longestBoost &&
                it.first.normalizedName.lowercase() !in boostedNameKeys
        }
        return RankedCandidates(exactAbove.map { it.first } + boosted.map { it.first } + below.map { it.first }, boosted.map { it.first.reportNo })
    }

    private fun namesOf(product: ProcessedFoodFeaturedCatalog.FeaturedProduct): List<String> =
        (listOf(product.name) + product.aliases).map(ProcessedFoodSnapshot::normalizeName).distinct()

    private fun representativeOf(product: ProcessedFoodFeaturedCatalog.FeaturedProduct, rows: List<ProcessedFoodProduct>): ProcessedFoodProduct? {
        val names = namesOf(product).map { it.lowercase() }.toSet()
        val named = rows.filter { it.normalizedName.lowercase() in names }
        val pool = named.filter { isMadeBy(it.displayMaker(), product.maker) }.ifEmpty { named.filter { it.normalizedName.equals(product.name, ignoreCase = true) } }
        return latestOf(pool.filter { it.normalizedName.equals(product.name, ignoreCase = true) }) ?: latestOf(pool)
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
        val reading = IngredientLabelReader.read(product.allergens, product.rawMaterials)
        val rating = reviews.aggregateRatingsByProcessedFoodIds(listOf(product.id)).singleOrNull()
        return ProcessedFoodDetailResponse.of(product, reading, memberId?.let { memberService.getAvoidance(it) }, rating)
    }

    private fun candidatesFor(tokens: List<String>, makerKeywords: List<String>, lastMirror: LocalDateTime?): List<ProcessedFoodProduct> {
        val seenSince = lastMirror?.minus(ProcessedFoodProduct.DISCONTINUED_AFTER) ?: NO_BASELINE
        return (
            tokens.flatMap { candidateIndex.nameCandidateIds(it, seenSince, CANDIDATE_LIMIT) } +
                makerKeywords.flatMap { candidateIndex.makerCandidateIds(it, seenSince, CANDIDATE_LIMIT) }
            ).distinct().let(products::findAllById)
    }

    companion object {
        private const val CANDIDATE_LIMIT = 300
        private const val MAX_OFFSET = 10_000
        private const val MAX_SCAN_TOKENS = 10
        private val NO_BASELINE: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0)
    }
}

data class RankedCandidates(val products: List<ProcessedFoodProduct>, val boostedReportNos: List<String>)
