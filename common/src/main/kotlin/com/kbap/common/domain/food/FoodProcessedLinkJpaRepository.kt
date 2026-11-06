package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.FoodProcessedLink
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FoodProcessedLinkJpaRepository : JpaRepository<FoodProcessedLink, Long> {
    fun findByFoodId(foodId: Long): FoodProcessedLink?

    fun findAllByFoodIdIn(foodIds: Collection<Long>): List<FoodProcessedLink>

    @Modifying
    @Query(
        nativeQuery = true,
        value = "INSERT INTO food_processed_link (food_id, processed_food_product_id, linked_by, status, created_at, updated_at) " +
            "VALUES (:foodId, :productId, :linkedBy, 'ACTIVE', NOW(6), NOW(6)) " +
            "ON DUPLICATE KEY UPDATE processed_food_product_id = VALUES(processed_food_product_id), linked_by = VALUES(linked_by), status = 'ACTIVE', updated_at = NOW(6)",
    )
    fun upsertLink(@Param("foodId") foodId: Long, @Param("productId") productId: Long, @Param("linkedBy") linkedBy: Long?): Int

    @Modifying
    @Query(
        nativeQuery = true,
        value = "UPDATE food_processed_link SET status = 'DELETED', updated_at = NOW(6) WHERE food_id = :foodId AND status = 'ACTIVE'",
    )
    fun unlink(@Param("foodId") foodId: Long): Int
}
