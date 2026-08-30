package com.kbap.api.ingredient

import com.kbap.api.IntegrationTest
import com.kbap.common.domain.ingredient.IngredientJpaRepository
import com.kbap.common.domain.ingredient.model.IngredientCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import javax.sql.DataSource

@IntegrationTest
class IngredientImageSeedTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var ingredientRepository: IngredientJpaRepository

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        beforeSpec {
            IngredientTestSeed.restoreCatalog(dataSource)
        }

        given("재료 카탈로그 시드") {
            `when`("전 재료를 조회하면") {
                then("건수가 식별자 enum 코드 수와 일치한다") {
                    ingredientRepository.count() shouldBe IngredientCode.entries.size.toLong()
                }

                then("모든 재료에 코드 규칙대로 이미지 경로가 적재되어 있다") {
                    ingredientRepository.findAll().forEach { ingredient ->
                        ingredient.imagePath shouldBe "images/webp/${ingredient.code.name.lowercase()}.webp"
                    }
                }
            }
        }
    }
}
