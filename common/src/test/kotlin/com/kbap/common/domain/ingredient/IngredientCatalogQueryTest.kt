package com.kbap.common.domain.ingredient

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.ingredient.model.Ingredient
import com.kbap.common.domain.ingredient.model.IngredientCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class IngredientCatalogQueryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var substanceJpaRepository: IngredientJpaRepository

    init {
        afterSpec { substanceJpaRepository.deleteAll() }

        fun saveSubstance(code: IngredientCode, koreanName: String): Ingredient =
            substanceJpaRepository.save(Ingredient(code = code, koreanName = koreanName))

        given("활성 성분 전체 목록 조회") {
            `when`("여러 성분을 저장하고 findAll 로 조회하면") {
                then("활성 성분 전체를 반환한다") {
                    substanceJpaRepository.deleteAll()
                    saveSubstance(IngredientCode.EGG, koreanName = "달걀")
                    saveSubstance(IngredientCode.WHEAT, koreanName = "밀")
                    saveSubstance(IngredientCode.PEANUT, koreanName = "땅콩")

                    substanceJpaRepository.findAll().map { it.code } shouldContainExactlyInAnyOrder listOf(
                        IngredientCode.EGG,
                        IngredientCode.WHEAT,
                        IngredientCode.PEANUT,
                    )
                }
            }

            `when`("성분 하나를 소프트삭제한 뒤 조회하면") {
                then("@SQLRestriction 으로 삭제 성분은 빠지고 살아있는 성분만 반환한다") {
                    substanceJpaRepository.deleteAll()
                    saveSubstance(IngredientCode.SHRIMP, koreanName = "새우")
                    val deleted = saveSubstance(IngredientCode.CRAB, koreanName = "게")
                    deleted.delete()
                    substanceJpaRepository.save(deleted)

                    substanceJpaRepository.findAll().map { it.code } shouldContainExactlyInAnyOrder listOf(
                        IngredientCode.SHRIMP,
                    )
                }
            }
        }
    }
}
