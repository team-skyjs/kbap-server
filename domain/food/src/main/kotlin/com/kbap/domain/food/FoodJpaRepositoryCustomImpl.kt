package com.kbap.domain.food

import com.kbap.domain.food.model.Food
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository

@Repository
internal class FoodJpaRepositoryCustomImpl : FoodJpaRepositoryCustom {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    // insert-or-ignore: korean_name unique 충돌 시 no-op(동시 등록 idempotent). 바인딩은 행당 4개(placeholder 개수와 결합).
    override fun upsertIncomplete(foods: List<Food>) {
        val rows = foods.joinToString(", ") { "(?, ?, ?, '{}', '{}', '[]', ?, 'ACTIVE', NOW(6), NOW(6))" }
        val query = entityManager.createNativeQuery(
            """
            insert into food (korean_name, description, spiciness, name_translations, description_translations,
                              avoidance_substances, content_status, status, created_at, updated_at)
            values $rows
            on duplicate key update id = id
            """.trimIndent(),
        )
        foods.forEachIndexed { index, food ->
            query.setParameter(index * 4 + 1, food.koreanName)
            query.setParameter(index * 4 + 2, food.description)
            query.setParameter(index * 4 + 3, food.spiciness)
            query.setParameter(index * 4 + 4, food.contentStatus.name)
        }
        query.executeUpdate()
    }
}
