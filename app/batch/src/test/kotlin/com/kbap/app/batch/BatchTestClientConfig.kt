package com.kbap.app.batch

import com.kbap.common.core.food.FoodAvoidanceAssessmentClient
import com.kbap.common.core.food.FoodAvoidanceAssessmentResult
import com.kbap.common.core.food.FoodDescriptionClient
import com.kbap.common.core.food.FoodDescriptionContent
import com.kbap.common.core.food.FoodNameTranslationClient
import com.kbap.common.core.food.TargetLanguageTexts
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

@TestConfiguration
class BatchTestClientConfig {
    @Bean
    fun avoidanceClient(): FoodAvoidanceAssessmentClient =
        FoodAvoidanceAssessmentClient { _, _ -> FoodAvoidanceAssessmentResult(emptyList(), 0) }

    @Bean
    fun nameTranslationClient(): FoodNameTranslationClient =
        FoodNameTranslationClient { korean ->
            TargetLanguageTexts(TargetLanguageTexts.TARGET_LANGUAGES.associateWith { "$korean-${it.code}" })
        }

    @Bean
    fun descriptionClient(): FoodDescriptionClient =
        FoodDescriptionClient { korean ->
            FoodDescriptionContent(
                "$korean 설명",
                TargetLanguageTexts(TargetLanguageTexts.TARGET_LANGUAGES.associateWith { "$korean-${it.code}" }),
            )
        }
}
