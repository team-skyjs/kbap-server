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

@Service
class ProcessedFoodService(
    private val products: ProcessedFoodProductJpaRepository,
    private val memberService: MemberService,
) {
    @Transactional(readOnly = true)
    fun searchProcessedFoodPage(query: String?, cursor: String?, size: Int): SearchPage<ProcessedFoodSummaryResponse> {
        val tokens = ProcessedFoodSearchRanker.tokensOf(query.orEmpty())
        if (tokens.isEmpty()) throw BusinessException(ErrorCode.BLANK_SEARCH_KEYWORD)
        val offset = cursor?.let { it.toIntOrNull()?.takeIf { value -> value >= 0 } ?: throw BusinessException(ErrorCode.INVALID_CURSOR) } ?: 0
        val latestSeenAt = products.findLatestSeenAt() ?: return SearchPage(emptyList(), false, null)
        val seenSince = latestSeenAt.minus(ProcessedFoodProduct.DISCONTINUED_AFTER)
        val candidates = tokens.flatMap { products.findSearchCandidates(LikeWildcards.escape(it), seenSince, CANDIDATE_LIMIT) }.distinctBy { it.id }
        val ranked = ProcessedFoodSearchRanker.rank(candidates, tokens)
        val hasNext = ranked.size > offset + size
        return SearchPage(
            items = ranked.drop(offset).take(size).map(ProcessedFoodSummaryResponse::from),
            hasNext = hasNext,
            nextCursor = if (hasNext) (offset + size).toString() else null,
        )
    }

    @Transactional(readOnly = true)
    fun getProcessedFoodDetail(id: Long, memberId: Long?): ProcessedFoodDetailResponse {
        val product = products.findById(id).orElseThrow { BusinessException(ErrorCode.PROCESSED_FOOD_NOT_FOUND) }
        if (product.isDiscontinued(products.findLatestSeenAt() ?: product.lastSeenAt)) {
            throw BusinessException(ErrorCode.PROCESSED_FOOD_DISCONTINUED)
        }
        val reading = IngredientLabelReader.read(product.allergens, product.rawMaterials)
        return ProcessedFoodDetailResponse.of(product, reading, memberId?.let { memberService.getAvoidance(it) })
    }

    companion object {
        private const val CANDIDATE_LIMIT = 300
    }
}
