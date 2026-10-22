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
    fun searchProcessedFoodPage(query: String?, cursor: String?, size: Int): SearchPage<ProcessedFoodSummaryResponse> {
        val tokens = ProcessedFoodSearchRanker.tokensOf(query.orEmpty())
        if (tokens.isEmpty()) throw BusinessException(ErrorCode.BLANK_SEARCH_KEYWORD)
        val offset = cursor?.let { it.toIntOrNull()?.takeIf { value -> value in 0..MAX_OFFSET } ?: throw BusinessException(ErrorCode.INVALID_CURSOR) } ?: 0
        val lastMirror = products.findLastCompletedMirrorStartedAt()
        val candidates = candidatesFor(tokens, (tokens + ProcessedFoodSearchRanker.rawTokensOf(query.orEmpty())).distinct(), lastMirror)
        val ranked = featuredFirst(ProcessedFoodSearchRanker.scored(candidates, tokens).sortedWith(ProcessedFoodSearchRanker.ORDER), tokens.joinToString(" "), tokens, lastMirror).products
        val hasNext = ranked.size > offset + size
        val page = ranked.drop(offset).take(size)
        val ratings = reviews.aggregateRatingsByProcessedFoodIds(page.map { it.id }).associateBy { it.processedFoodId }
        return SearchPage(
            items = page.map { ProcessedFoodSummaryResponse.withReviews(it, ratings[it.id]) },
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
            val rows = products.findAllByNormalizedNameIn(featured.map { it.product.name }).filterNot { it.isDiscontinued(lastMirror) }
            featured.mapNotNull { boost ->
                latestOf(rows.filter { it.normalizedName == boost.product.name })?.let { it to boost.matchLength }
                    ?: null.also { log.warn("가공식품 대표 상품이 DB 에 없어 건너뜁니다 name={} maker={}", boost.product.name, boost.product.maker) }
            }
        }
        val boostedNames = boosted.map { it.first.normalizedName }.toSet()
        val boostedGroups = boosted.map { groupKeyOf(it.first) }.toSet()
        val rest = ranked.filterNot { it.first.normalizedName in boostedNames || groupKeyOf(it.first) in boostedGroups }
            .groupBy { groupKeyOf(it.first) }.values
            .map { group -> latestOf(group.map { it.first })!! to group.first().second }
        val longestBoost = boosted.maxOfOrNull { it.second } ?: 0
        val (exactAbove, below) = rest.partition {
            boosted.isNotEmpty() && ProcessedFoodSearchRanker.isExact(it.second) && it.first.normalizedName.length >= longestBoost
        }
        return RankedCandidates(exactAbove.map { it.first } + boosted.map { it.first } + below.map { it.first }, boosted.map { it.first.reportNo })
    }

    private fun latestOf(rows: List<ProcessedFoodProduct>): ProcessedFoodProduct? =
        rows.maxWithOrNull(compareBy<ProcessedFoodProduct>({ it.lastSeenAt }, { it.id }))

    private fun groupKeyOf(product: ProcessedFoodProduct): String {
        val maker = makerKeyOf(product)
        val name = product.normalizedName.removePrefix(maker).ifEmpty { product.normalizedName }
        return "$name|$maker"
    }

    private fun makerKeyOf(product: ProcessedFoodProduct): String = product.displayMaker()?.let(ProcessedFoodSnapshot::normalizeName).orEmpty()

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
