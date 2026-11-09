package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.EntityStatus
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.domain.processedfood.model.ProcessedFoodRecipe
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminProcessedFoodDetailService(
    private val jdbc: JdbcTemplate,
) {
    @Transactional(readOnly = true)
    fun getProduct(id: Long): AdminProcessedFoodDetailResponse =
        jdbc.query(
            "SELECT id, product_name, normalized_name, source, status, kcal_total, capacity, manufacturer, image_url, cutout_image_url, cutout_review_status " +
                "FROM processed_food_product WHERE id = ?",
            { rs, _ ->
                val reviewStatus = rs.getString("cutout_review_status")
                val cutout = rs.getString("cutout_image_url").takeIf { reviewStatus == "APPROVED" }
                AdminProcessedFoodDetailResponse(
                    processedFoodId = rs.getLong("id"),
                    productName = rs.getString("product_name"),
                    normalizedName = rs.getString("normalized_name"),
                    source = rs.getString("source"),
                    status = EntityStatus.valueOf(rs.getString("status")),
                    kcalTotal = rs.getInt("kcal_total").takeUnless { rs.wasNull() },
                    capacity = rs.getString("capacity"),
                    manufacturer = rs.getString("manufacturer"),
                    imageUrl = ProcessedFoodProduct.secureImageUrl(cutout ?: rs.getString("image_url")),
                    cutoutReviewStatus = reviewStatus,
                )
            },
            id,
        ).firstOrNull() ?: throw BusinessException(ErrorCode.PROCESSED_FOOD_NOT_FOUND)

    @Transactional(readOnly = true)
    fun getRecipe(id: Long): AdminProcessedFoodRecipeResponse =
        jdbc.query(
            "SELECT id, recipe FROM processed_food_product WHERE id = ?",
            { rs, _ -> AdminProcessedFoodRecipeResponse(rs.getLong("id"), ProcessedFoodRecipe.fromJsonOrNull(rs.getString("recipe"))) },
            id,
        ).firstOrNull() ?: throw BusinessException(ErrorCode.PROCESSED_FOOD_NOT_FOUND)
}
