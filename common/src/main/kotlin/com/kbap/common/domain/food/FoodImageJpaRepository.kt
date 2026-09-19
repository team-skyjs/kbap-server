package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.FoodImage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FoodImageJpaRepository : JpaRepository<FoodImage, Long> {
    fun promoteAsPrimary(foodId: Long, imageKey: String) {
        demotePrimaryByFoodId(foodId)
        val existing = findByFoodIdAndImageKey(foodId, imageKey)
        if (existing != null) {
            existing.isPrimary = true
            save(existing)
        } else {
            save(FoodImage.primary(foodId, imageKey))
        }
    }

    @Modifying(flushAutomatically = true)
    @Query("update FoodImage fi set fi.isPrimary = false where fi.foodId = :foodId and fi.isPrimary = true")
    fun demotePrimaryByFoodId(@Param("foodId") foodId: Long): Int

    fun findByFoodIdAndImageKey(foodId: Long, imageKey: String): FoodImage?

    fun findByFoodIdAndIsPrimaryTrue(foodId: Long): FoodImage?

    fun findByFoodIdOrderBySortOrderAscIdAsc(foodId: Long): List<FoodImage>
}
