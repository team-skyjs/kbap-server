package com.kbap.common.domain.review

import com.kbap.common.domain.review.model.Review
import jakarta.persistence.EntityManager

class ReviewRepositoryCustomImpl(
    private val entityManager: EntityManager,
) : ReviewRepositoryCustom {
    override fun findReviewPage(
        foodId: Long?,
        countryCode: String?,
        minRating: Int?,
        maxRating: Int?,
        sort: ReviewSort,
        metricCursor: Long?,
        idCursor: Long?,
        excludedMemberIds: List<Long>,
        excludedReviewIds: List<Long>,
        limit: Int,
    ): List<ReviewPageRow> {
        val conditions = mutableListOf(
            "r.memberId not in :excludedMemberIds",
            "r.id not in :excludedReviewIds",
            "exists (select 1 from Food f where f.id = r.foodId)",
        )
        if (foodId != null) conditions += "r.foodId = :foodId"
        if (countryCode != null) conditions += "r.authorCountryCode = :countryCode"
        if (minRating != null) conditions += "r.rating >= :minRating"
        if (maxRating != null) conditions += "r.rating <= :maxRating"

        val metricExpr = when (sort) {
            ReviewSort.LATEST -> "r.id"
            ReviewSort.RATING_DESC, ReviewSort.RATING_ASC -> "r.rating"
            ReviewSort.FOOD_REVIEW_COUNT_DESC -> "(select count(r2) from Review r2 where r2.foodId = r.foodId)"
            ReviewSort.HELPFUL_DESC -> "count(l)"
        }
        val hasCursor = idCursor != null
        val cursorCondition = when (sort) {
            ReviewSort.LATEST -> "r.id < :idCursor"
            ReviewSort.RATING_ASC -> "($metricExpr > :metricCursor or ($metricExpr = :metricCursor and r.id < :idCursor))"
            else -> "($metricExpr < :metricCursor or ($metricExpr = :metricCursor and r.id < :idCursor))"
        }
        val orderDirection = if (sort == ReviewSort.RATING_ASC) "asc" else "desc"

        val jpql = if (sort == ReviewSort.HELPFUL_DESC) {
            buildString {
                append("select r, count(l) from Review r left join ReviewLike l on l.reviewId = r.id")
                append(" where ").append(conditions.joinToString(" and "))
                append(" group by r")
                if (hasCursor) append(" having ").append(cursorCondition)
                append(" order by count(l) desc, r.id desc")
            }
        } else {
            buildString {
                if (hasCursor) conditions += cursorCondition
                append("select r, $metricExpr from Review r")
                append(" where ").append(conditions.joinToString(" and "))
                append(" order by $metricExpr $orderDirection, r.id desc")
            }
        }

        val query = entityManager.createQuery(jpql)
            .setParameter("excludedMemberIds", excludedMemberIds)
            .setParameter("excludedReviewIds", excludedReviewIds)
            .setMaxResults(limit)
        if (foodId != null) query.setParameter("foodId", foodId)
        if (countryCode != null) query.setParameter("countryCode", countryCode)
        if (minRating != null) query.setParameter("minRating", minRating)
        if (maxRating != null) query.setParameter("maxRating", maxRating)
        if (hasCursor) {
            query.setParameter("idCursor", idCursor)
            when (sort) {
                ReviewSort.LATEST -> Unit
                ReviewSort.RATING_DESC, ReviewSort.RATING_ASC ->
                    query.setParameter("metricCursor", requireNotNull(metricCursor).toInt())
                else -> query.setParameter("metricCursor", requireNotNull(metricCursor))
            }
        }

        return query.resultList.map { row ->
            val columns = row as Array<*>
            ReviewPageRow(review = columns[0] as Review, metric = (columns[1] as Number).toLong())
        }
    }
}
