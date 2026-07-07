package com.meogo.infra.persistence.research

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FoodCandidateJpaRepository : JpaRepository<FoodCandidateJpaEntity, Long> {
    fun findByKoreanName(koreanName: String): FoodCandidateJpaEntity?

    @Query(
        nativeQuery = true,
        value = """
            select * from food_candidate
            where status = 'ACTIVE'
              and published_food_id is null
              and description is not null
              and json_length(description_translations) = 9
              and json_length(substance_mapping) > 0
              and id > :afterId
            order by id asc
            limit :size
        """,
    )
    fun findPromotable(
        @Param("afterId") afterId: Long,
        @Param("size") size: Int,
    ): List<FoodCandidateJpaEntity>

    @Modifying(clearAutomatically = true)
    @Query("update FoodCandidateJpaEntity c set c.publishedFoodId = :foodId where c.id = :id")
    fun markPublished(
        @Param("id") id: Long,
        @Param("foodId") foodId: Long,
    )

    @Modifying(clearAutomatically = true)
    @Query("update FoodCandidateJpaEntity c set c.substanceMapping = :mapping where c.id = :id")
    fun updateSubstanceMapping(
        @Param("id") id: Long,
        @Param("mapping") mapping: List<SubstanceMappingJson>,
    )

    @Modifying(clearAutomatically = true)
    @Query("update FoodCandidateJpaEntity c set c.descriptionTranslations = :translations where c.id = :id")
    fun updateDescriptionTranslations(
        @Param("id") id: Long,
        @Param("translations") translations: Map<String, String>,
    )
}
