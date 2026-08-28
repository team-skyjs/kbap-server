package com.kbap.common.domain.food

import com.kbap.common.domain.food.dto.AdminFoodRow
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentStatus
import jakarta.persistence.EntityManager
import java.sql.Timestamp
import java.time.LocalDateTime

class FoodAdminQueryRepositoryCustomImpl(
    private val entityManager: EntityManager,
) : FoodAdminQueryRepositoryCustom {
    override fun findAdminPage(filter: AdminFoodFilter, page: Int, size: Int): AdminFoodRows {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        if (!filter.includeDeleted) conditions += "f.status = 'ACTIVE'"
        filter.q?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            q.toLongOrNull()?.let { conditions += "f.id = :id"; params["id"] = it }
                ?: run { conditions += "f.display_name like :kw"; params["kw"] = "%$q%" }
        }
        filter.ingredient?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions += "json_search(f.ingredients, 'one', :ingredient, null, '$[*].code') is not null"
            params["ingredient"] = it
        }
        filter.translation?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions += "json_search(f.name_translations, 'one', :translation, null, '$.*') is not null"
            params["translation"] = "%$it%"
        }
        filter.status?.let { conditions += "f.content_status = :status"; params["status"] = it.name }
        filter.failureKind?.let { conditions += "f.content_failure_kind = :failureKind"; params["failureKind"] = it.name }
        val where = if (conditions.isEmpty()) "" else " where " + conditions.joinToString(" and ")
        val order = " order by ${filter.sort.column} ${if (filter.descending) "desc" else "asc"}, f.id desc"

        val rows = entityManager.createNativeQuery(
            "select f.id, f.korean_name, f.display_name, f.content_status, f.content_failure_kind, f.spiciness, " +
                "f.image_ref, f.content_review_attempts, f.status, f.updated_at from food f$where$order",
        )
            .apply { params.forEach { (k, v) -> setParameter(k, v) } }
            .setFirstResult((page - 1) * size)
            .setMaxResults(size)
            .resultList
            .map { toRow(it as Array<*>) }
        val total = entityManager.createNativeQuery("select count(*) from food f$where")
            .apply { params.forEach { (k, v) -> setParameter(k, v) } }
            .singleResult as Number
        return AdminFoodRows(rows = rows, totalCount = total.toLong())
    }

    private fun toRow(r: Array<*>): AdminFoodRow =
        AdminFoodRow(
            id = (r[0] as Number).toLong(),
            koreanName = r[1] as String,
            displayName = r[2] as String,
            contentStatus = FoodContentStatus.valueOf(r[3] as String),
            contentFailureKind = (r[4] as String?)?.let { FoodContentFailureKind.valueOf(it) },
            spiciness = (r[5] as Number).toInt(),
            imageRef = r[6] as String?,
            contentReviewAttempts = (r[7] as Number).toInt(),
            deleted = r[8] != "ACTIVE",
            updatedAt = toLocalDateTime(r[9]),
        )

    private fun toLocalDateTime(value: Any?): LocalDateTime = when (value) {
        is LocalDateTime -> value
        is Timestamp -> value.toLocalDateTime()
        else -> error("updated_at 을 해석할 수 없습니다: $value")
    }
}
