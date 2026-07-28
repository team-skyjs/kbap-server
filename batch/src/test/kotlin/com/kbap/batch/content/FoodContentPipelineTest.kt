package com.kbap.batch.content

import com.kbap.batch.BatchTestClientConfig
import com.kbap.common.domain.LanguageCode
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodAvoidanceItem
import com.kbap.common.domain.food.model.FoodContentStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest
@Import(MySqlContainerConfig::class, BatchTestClientConfig::class)
class FoodContentPipelineTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var foodContentProcessor: FoodContentItemProcessor

    @Autowired
    @Qualifier("foodContentWriter")
    private lateinit var foodContentWriter: ItemWriter<Food>

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    init {
        val targets = LanguageCode.entries.filter { it != LanguageCode.KO }
            .associate { it.code to "t-${it.code}" }

        fun saveProgress(food: Food) = foodContentProcessor.saveProgress(food)

        given("진행 저장의 독립 커밋") {
            `when`("진행 저장 뒤 바깥 트랜잭션(청크)이 롤백되면") {
                then("진행 저장 시점의 변경은 커밋으로 유지된다") {
                    val id = foodJpaRepository.save(Food.incomplete("독립커밋-갈비탕")).id

                    TransactionTemplate(transactionManager).execute { status ->
                        val food = foodJpaRepository.findById(id).get()
                        food.description = "진하게 우린 갈비탕"
                        saveProgress(food)
                        status.setRollbackOnly()
                    }

                    foodJpaRepository.findById(id).get().description shouldBe "진하게 우린 갈비탕"
                }
            }
        }

        given("라이터의 수렴 전이 — 텍스트 완료·이미지 없음") {
            `when`("텍스트 3작업만 채워진 음식을 라이터에 넘기면") {
                then("이미지 대기실 PENDING_IMAGE 로 전이되어 저장된다") {
                    val food = foodJpaRepository.save(Food.incomplete("텍스트완료-잡채"))
                    food.description = "쫄깃한 잡채"
                    food.spiciness = 0
                    food.nameTranslations = targets
                    food.descriptionTranslations = targets
                    food.avoidanceSubstances = emptyList()

                    foodContentWriter.write(Chunk(mutableListOf(food)))

                    foodJpaRepository.findById(food.id).get().contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
                }
            }
        }

        given("라이터의 검수 대기 전이") {
            `when`("콘텐츠가 완비된 음식을 라이터에 넘기면") {
                then("PENDING_REVIEW 로 전이되어 저장된다") {
                    val food = foodJpaRepository.save(Food.incomplete("완비-갈비찜"))
                    food.imageRef = "s3://img/galbijjim.jpg"
                    food.description = "달큰한 갈비찜"
                    food.spiciness = 2
                    food.nameTranslations = targets
                    food.descriptionTranslations = targets
                    food.avoidanceSubstances = listOf(FoodAvoidanceItem("SOYBEAN", 100))

                    foodContentWriter.write(Chunk(mutableListOf(food)))

                    foodJpaRepository.findById(food.id).get().contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                }
            }

            `when`("미완비 음식만 라이터에 넘기면") {
                then("전이도 저장도 하지 않고 INCOMPLETE 로 남는다") {
                    val food = foodJpaRepository.save(Food.incomplete("미완비-잡채"))

                    foodContentWriter.write(Chunk(mutableListOf(food)))

                    foodJpaRepository.findById(food.id).get().contentStatus shouldBe FoodContentStatus.INCOMPLETE
                }
            }
        }
    }
}
