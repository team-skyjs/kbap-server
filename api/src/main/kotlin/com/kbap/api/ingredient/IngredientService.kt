package com.kbap.api.ingredient

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.ingredient.model.DietCategory
import com.kbap.common.domain.ingredient.IngredientJpaRepository
import com.kbap.common.util.ImageUrls
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class IngredientService(
    private val ingredientRepository: IngredientJpaRepository,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    @Transactional(readOnly = true)
    fun getIngredients(lang: LanguageCode): IngredientListResponse =
        IngredientListResponse(
            ingredients = ingredientRepository.findAll(Sort.by("id")).map { ingredient ->
                IngredientItemResponse(
                    code = ingredient.code.name,
                    name = ingredient.displayName(lang),
                    imageUrl = ImageUrls.resolve(imagePublicBaseUrl, ingredient.imagePath),
                )
            },
        )

    @Transactional(readOnly = true)
    fun getDietIngredientMappings(lang: LanguageCode): DietListResponse {
        val ingredientsByCode = ingredientRepository.findAll().associateBy { it.code }
        return DietListResponse(
            diets = DietCategory.entries.map { category ->
                DietItemResponse(
                    code = category.name,
                    name = category.koreanName,
                    ingredients = category.avoidedIngredients
                        .mapNotNull { ingredientsByCode[it] }
                        .sortedBy { it.id }
                        .map { DietIngredientResponse(id = it.id, code = it.code.name, name = it.displayName(lang)) },
                )
            },
        )
    }
}
