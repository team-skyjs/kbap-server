package com.kbap.api.scan

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.food.FoodService
import com.kbap.api.member.MemberService
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.vector.FoodVectorMatch
import com.kbap.common.domain.food.vector.FoodVectorSearcher
import com.kbap.common.domain.ingredient.IngredientJpaRepository
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import com.kbap.common.port.llm.ExtractedMenu
import com.kbap.common.port.llm.MenuBoardVisionExtractor
import com.kbap.common.port.llm.OcrItem
import com.kbap.common.port.llm.TextEmbeddingClient
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class ScanSimilarSubstitutionTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var foodService: FoodService
    @Autowired private lateinit var foodRepository: FoodJpaRepository
    @Autowired private lateinit var scanHistoryRepository: ScanHistoryJpaRepository
    @Autowired private lateinit var memberService: MemberService
    @Autowired private lateinit var ingredientRepository: IngredientJpaRepository
    @Autowired private lateinit var memberRepository: MemberJpaRepository
    @Autowired private lateinit var dataSource: DataSource

    init {
        beforeEach { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("유사 음식 대체 — 정규화 키가 같은 두 표기 (#419 Codex 지적)") {
            `when`("한 스캔에 '유사테스트 찌개'·'유사테스트찌개' 처럼 같은 키의 신규 라벨이 둘 오면") {
                then("대표 하나만이 아니라 두 항목 모두 같은 공개 음식으로 대체된다") {
                    val target = foodRepository.save(Food(koreanName = "공개대체찌개", description = "설명", contentStatus = FoodContentStatus.READY))
                    val member = memberRepository.save(Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "similar-${System.nanoTime()}", null)))
                    val vision = object : MenuBoardVisionExtractor {
                        override fun extract(imagePath: String, ocrItems: List<OcrItem>) = listOf(
                            ExtractedMenu("Similar stew", "유사테스트 찌개", 9000, null),
                            ExtractedMenu("Similar stew", "유사테스트찌개", 9500, null),
                        )
                    }
                    val embedded = mutableListOf<String>()
                    val embedding = TextEmbeddingClient { texts -> embedded += texts; texts.map { FloatArray(4) } }
                    val searcher = FoodVectorSearcher { _, _ -> listOf(FoodVectorMatch(target.id, 0.99)) }
                    val service = ScanService(foodService, vision, scanHistoryRepository, ingredientRepository, SimilarFoodService(embedding, searcher, foodService), memberService)

                    val result = service.scan(member, "scans/x.jpg", emptyList(), LanguageCode.EN, requireDetectedMenu = false, substituteSimilar = true)

                    embedded.size shouldBe 1
                    result.items.map { it.substituted } shouldBe listOf(true, true)
                    result.items.map { it.foodId } shouldBe listOf(target.id, target.id)
                    result.items.map { it.originalScannedName } shouldBe listOf("유사테스트 찌개", "유사테스트찌개")
                }
            }
        }
    }
}
