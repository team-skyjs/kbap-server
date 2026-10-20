package com.kbap.api.processedfood

import com.kbap.api.core.SearchPage
import com.kbap.api.member.MemberService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.ingredient.label.IngredientLabelReader
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.util.LikeWildcards
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class ProcessedFoodService(
    private val products: ProcessedFoodProductJpaRepository,
    private val memberService: MemberService,
) {
    @Transactional(readOnly = true)
    fun searchProcessedFoodPage(query: String?, cursor: String?, size: Int): SearchPage<ProcessedFoodSummaryResponse> {
        val tokens = ProcessedFoodSearchRanker.tokensOf(query.orEmpty())
        if (tokens.isEmpty()) throw BusinessException(ErrorCode.BLANK_SEARCH_KEYWORD)
        val offset = cursor?.let { it.toIntOrNull()?.takeIf { value -> value in 0..MAX_OFFSET } ?: throw BusinessException(ErrorCode.INVALID_CURSOR) } ?: 0
        val candidates = candidatesFor(tokens, (tokens + ProcessedFoodSearchRanker.rawTokensOf(query.orEmpty())).distinct())
        val ranked = ProcessedFoodSearchRanker.rank(candidates, tokens)
        val hasNext = ranked.size > offset + size
        return SearchPage(
            items = ranked.drop(offset).take(size).map(ProcessedFoodSummaryResponse::from),
            hasNext = hasNext,
            nextCursor = if (hasNext) (offset + size).toString() else null,
        )
    }

    @Transactional(readOnly = true)
    fun getProcessedFoodDetail(id: Long, memberId: Long?): ProcessedFoodDetailResponse =
        detailOf(available(products.findById(id).orElse(null)), memberId)

    @Transactional(readOnly = true)
    fun getProcessedFoodDetailByReportNo(reportNo: String, memberId: Long?): ProcessedFoodDetailResponse =
        detailOf(available(products.findByReportNo(reportNo)), memberId)

    @Transactional(readOnly = true)
    fun getNameScanCandidates(queries: List<String>, limit: Int): List<ProcessedFoodProduct> {
        val tokensByQuery = queries.map(ProcessedFoodSearchRanker::tokensOf).filter { it.isNotEmpty() }
        if (tokensByQuery.isEmpty()) return emptyList()
        val tokens = tokensByQuery.flatten().distinct().take(MAX_SCAN_TOKENS)
        val rawTokens = queries.flatMap(ProcessedFoodSearchRanker::rawTokensOf).distinct().take(MAX_SCAN_TOKENS)
        val candidates = candidatesFor(tokens, (tokens + rawTokens).distinct())
        val best = linkedMapOf<Long, Pair<ProcessedFoodProduct, ProcessedFoodSearchScore>>()
        tokensByQuery.forEach { queryTokens ->
            ProcessedFoodSearchRanker.scored(candidates, queryTokens).forEach { scored ->
                best.merge(scored.first.id, scored) { kept, next -> if (ProcessedFoodSearchRanker.ORDER.compare(kept, next) <= 0) kept else next }
            }
        }
        return best.values.sortedWith(ProcessedFoodSearchRanker.ORDER).take(limit).map { it.first }
    }

    private fun available(product: ProcessedFoodProduct?): ProcessedFoodProduct {
        if (product == null) throw BusinessException(ErrorCode.PROCESSED_FOOD_NOT_FOUND)
        if (product.isDiscontinued(products.findLastCompletedMirrorStartedAt())) {
            throw BusinessException(ErrorCode.PROCESSED_FOOD_DISCONTINUED)
        }
        return product
    }

    private fun detailOf(product: ProcessedFoodProduct, memberId: Long?): ProcessedFoodDetailResponse {
        val reading = IngredientLabelReader.read(product.allergens, product.rawMaterials)
        return ProcessedFoodDetailResponse.of(product, reading, memberId?.let { memberService.getAvoidance(it) })
    }

    private fun candidatesFor(tokens: List<String>, makerKeywords: List<String>): List<ProcessedFoodProduct> {
        val seenSince = products.findLastCompletedMirrorStartedAt()?.minus(ProcessedFoodProduct.DISCONTINUED_AFTER) ?: NO_BASELINE
        return (
            tokens.flatMap { products.findNameCandidates(it, LikeWildcards.escape(it), seenSince, CANDIDATE_LIMIT) } +
                makerKeywords.flatMap { products.findMakerCandidates(LikeWildcards.escape(it), seenSince, CANDIDATE_LIMIT) }
            ).distinctBy { it.id }
    }

    companion object {
        private const val CANDIDATE_LIMIT = 300
        private const val MAX_OFFSET = 10_000
        private const val MAX_SCAN_TOKENS = 10
        private val NO_BASELINE: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0)
    }
}
