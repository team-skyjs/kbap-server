package com.kbap.common.domain.review

import com.kbap.common.domain.review.model.Review
import jakarta.persistence.EntityManager

class ReviewAdminQueryRepositoryCustomImpl(
    private val entityManager: EntityManager,
) : ReviewAdminQueryRepositoryCustom {
    override fun findAdminPage(filter: AdminReviewFilter, page: Int, size: Int): AdminReviewRows {
        val conditions = mutableListOf("fr.status = 'ACTIVE'")
        val params = mutableMapOf<String, Any>()
        filter.q?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            q.toLongOrNull()?.let { conditions += "fr.id = :id"; params["id"] = it }
                ?: run { conditions += "fr.content like :kw"; params["kw"] = "%$q%" }
        }
        filter.memberId?.let { conditions += "fr.member_id = :memberId"; params["memberId"] = it }
        filter.foodId?.let { conditions += "fr.food_id = :foodId"; params["foodId"] = it }
        filter.reported?.let {
            val exists = "exists (select 1 from report r where r.target_type = 'REVIEW' and r.target_id = fr.id and r.status = 'ACTIVE')"
            conditions += if (it) exists else "not $exists"
        }
        filter.hasImage?.let {
            val has = "(fr.image_refs is not null and json_length(fr.image_refs) > 0)"
            conditions += if (it) has else "not $has"
        }
        val where = " where " + conditions.joinToString(" and ")

        @Suppress("UNCHECKED_CAST")
        val rows = entityManager.createNativeQuery("select fr.* from food_review fr$where order by fr.id desc", Review::class.java)
            .apply { params.forEach { (k, v) -> setParameter(k, v) } }
            .setFirstResult((page - 1) * size)
            .setMaxResults(size)
            .resultList as List<Review>
        val total = entityManager.createNativeQuery("select count(*) from food_review fr$where")
            .apply { params.forEach { (k, v) -> setParameter(k, v) } }
            .singleResult as Number
        return AdminReviewRows(rows = rows, totalCount = total.toLong())
    }
}
