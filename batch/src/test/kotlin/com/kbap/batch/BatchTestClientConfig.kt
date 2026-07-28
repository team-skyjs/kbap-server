package com.kbap.batch

import com.kbap.common.port.llm.FoodAvoidanceAssessmentClient
import com.kbap.common.port.llm.FoodAvoidanceAssessmentResult
import com.kbap.common.port.llm.FoodDescriptionClient
import com.kbap.common.port.llm.FoodDescriptionContent
import com.kbap.common.port.llm.FoodNameTranslationClient
import com.kbap.common.domain.food.model.TargetLanguageTexts
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
