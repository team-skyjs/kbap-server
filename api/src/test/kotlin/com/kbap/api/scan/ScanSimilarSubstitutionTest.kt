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

    @Autowired private lateinit var fallbackPolicies: List<MenuFallbackPolicy>
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

        given("기본 벡터 설정") {
            then("AI 정책만 등록된다") {
                fallbackPolicies.size shouldBe 1
                (fallbackPolicies.single() is AiEstimatePolicy) shouldBe true
            }
        }

        given("미등록 메뉴 일부에만 벡터 검색 결과가 있는 경우") {
            then("허용된 요청에서만 검색하고 나머지 이름은 AI 추정 대상으로 돌려준다") {
                val target = foodRepository.save(Food(koreanName = "공개대체국", description = "설명", contentStatus = FoodContentStatus.READY))
                val calls = mutableListOf<List<String>>()
                val embedding = TextEmbeddingClient { names ->
                    calls.add(names)
                    names.mapIndexed { index, _ -> floatArrayOf(index.toFloat()) }
                }
                val searcher = FoodVectorSearcher { vector, _ ->
                    if (vector.first() == 0f) listOf(FoodVectorMatch(target.id, 0.99)) else emptyList()
                }
                val vectorPolicy = VectorMatchPolicy(SimilarFoodService(embedding, searcher, foodService))
                val matching = MenuMatchingService(foodService, listOf(vectorPolicy, AiEstimatePolicy()))
                val menus = listOf(
                    ExtractedMenu("Soup", "미등록국", 9000, null),
                    ExtractedMenu("Rice", "미등록밥", 8000, null),
                )

                val withoutVector = matching.matchMenus(menus, allowVectorMatch = false)
                calls.size shouldBe 0
                withoutVector.values.map { it.source } shouldBe List(2) { MenuMatchSource.PENDING_AI_ESTIMATE }

                val withVector = matching.matchMenus(menus, allowVectorMatch = true)
                calls shouldBe listOf(listOf("미등록국", "미등록밥"))
                withVector.getValue("미등록국").source shouldBe MenuMatchSource.VECTOR
                withVector.getValue("미등록밥").source shouldBe MenuMatchSource.PENDING_AI_ESTIMATE
            }
        }

        given("유사 음식 대체 — 정규화 키가 같은 두 표기 (#419 Codex 지적)") {
            `when`("한 스캔에 '유사테스트 찌개'·'유사테스트찌개' 처럼 같은 키의 신규 라벨이 둘 오면") {
                then("대표 하나만이 아니라 두 항목 모두 같은 공개 음식으로 대체되고, 출처 연결은 대체 음식과 원래(미완성) 음식 둘 다 남는다 — Codex P2(#588)") {
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
                    val vectorPolicy = VectorMatchPolicy(SimilarFoodService(embedding, searcher, foodService))
                    val matching = MenuMatchingService(foodService, listOf(vectorPolicy, AiEstimatePolicy()))
                    val service = ScanService(foodService, vision, scanHistoryRepository, ingredientRepository, matching, memberService)

                    val result = service.scan(member, "scans/x.jpg", emptyList(), LanguageCode.EN, requireDetectedMenu = false, substituteSimilar = true)

                    embedded.size shouldBe 1
                    result.items.map { it.substituted } shouldBe listOf(true, true)
                    result.items.map { it.foodId } shouldBe listOf(target.id, target.id)
                    result.items.map { it.originalScannedName } shouldBe listOf("유사테스트 찌개", "유사테스트찌개")

                    val original = foodRepository.findByKoreanNameIn(setOf("유사테스트찌개")).single()
                    result.items.map { it.substitutedFromFoodId } shouldBe listOf(original.id, original.id)
                    com.kbap.api.scanlog.ScanResultSummaries.menu(result).foodLinks.map { it.foodId to it.matched } shouldBe
                        listOf(target.id to true, original.id to false)
                }
            }
        }

        given("유사 음식 대체 — 행은 있으나 READY 가 아닌 이름(S-959)") {
            `when`("스캔한 이름의 음식 행이 FAILED 로만 있으면") {
                then("벡터 대체에도 보내 공개 음식으로 대체한다(예전엔 새 이름만 보내 matched=false 로 끝났다)") {
                    val target = foodRepository.save(Food(koreanName = "공개김치찌개", description = "설명", contentStatus = FoodContentStatus.READY))
                    foodRepository.save(Food(koreanName = "실패김치찌개", description = "", contentStatus = FoodContentStatus.FAILED))
                    val member = memberRepository.save(Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "similar-${System.nanoTime()}", null)))
                    val vision = object : MenuBoardVisionExtractor {
                        override fun extract(imagePath: String, ocrItems: List<OcrItem>) = listOf(ExtractedMenu("Kimchi stew", "실패김치찌개", 9000, null))
                    }
                    val embedded = mutableListOf<String>()
                    val embedding = TextEmbeddingClient { texts -> embedded += texts; texts.map { FloatArray(4) } }
                    val searcher = FoodVectorSearcher { _, _ -> listOf(FoodVectorMatch(target.id, 0.9)) }
                    val vectorPolicy = VectorMatchPolicy(SimilarFoodService(embedding, searcher, foodService))
                    val matching = MenuMatchingService(foodService, listOf(vectorPolicy, AiEstimatePolicy()))
                    val service = ScanService(foodService, vision, scanHistoryRepository, ingredientRepository, matching, memberService)

                    val result = service.scan(member, "scans/y.jpg", emptyList(), LanguageCode.EN, requireDetectedMenu = false, substituteSimilar = true)

                    embedded shouldBe listOf("실패김치찌개")
                    result.items.single().let {
                        it.substituted shouldBe true
                        it.matched shouldBe true
                        it.foodId shouldBe target.id
                    }
                }
            }
        }
    }
}
