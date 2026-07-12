package com.meogo.application.client.home

import com.meogo.application.client.food.usecase.MemberAvoidedSubstanceProvider
import com.meogo.core.avoidance.AvoidanceSubstance
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.avoidance.AvoidanceSubstanceRepository
import com.meogo.core.food.Food
import com.meogo.core.food.FoodAvoidanceSubstance
import com.meogo.core.food.FoodContent
import com.meogo.core.food.FoodRepository
import com.meogo.core.food.FoodSpiciness
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.lang.LocalizedText
import com.meogo.core.kernel.risk.RiskLevel
import com.meogo.core.member.AvoidanceSubstanceCodeRef
import com.meogo.core.member.Member
import com.meogo.core.member.MemberProfile
import com.meogo.core.member.MemberRepository
import com.meogo.core.member.SocialIdentity
import com.meogo.core.member.SocialProvider
import com.meogo.core.scan.ScanHistory
import com.meogo.core.scan.ScanHistoryRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import com.meogo.core.food.AvoidanceSubstanceCodeRef as FoodAvoidanceSubstanceCodeRef

private class HomeFakeMemberRepository(private val members: Map<Long, Member>) : MemberRepository {
    override fun findById(id: Long): Member? = members[id]
    override fun findByIdentity(provider: SocialProvider, providerUserId: String): Member? = null
    override fun saveNew(member: Member): Member = member
    override fun update(member: Member): Member = member
    override fun withdraw(id: Long) = Unit
}

private class HomeFakeFoodRepository(
    private val readyById: Map<Long, Food>,
    private val randomOrder: List<Long> = readyById.keys.toList(),
) : FoodRepository {
    override fun findById(id: Long): Food? = readyById[id]
    override fun findFoodPage(cursor: Long?, size: Int): List<Food> = emptyList()
    override fun searchFoodPage(keyword: String, lang: LanguageCode, cursor: Long?, size: Int): List<Food> = emptyList()
    override fun findByKoreanMatchKeys(keys: Set<String>): Map<String, Food> = emptyMap()
    override fun createIncomplete(koreanNames: Set<String>): Map<String, Food> = emptyMap()
    override fun findRandomReady(size: Int): List<Food> =
        randomOrder.mapNotNull { readyById[it] }.take(size)
    override fun findAllReadyByIds(ids: List<Long>): List<Food> =
        ids.mapNotNull { readyById[it] }.shuffled()
}

private class HomeFakeScanHistoryRepository(private val recentFoodIds: List<Long>) : ScanHistoryRepository {
    override fun saveAll(records: List<ScanHistory>) = Unit
    override fun findRecentReadyFoodIds(memberId: Long, limit: Int): List<Long> = recentFoodIds.take(limit)
}

private class HomeFakeAvoidanceSubstanceRepository : AvoidanceSubstanceRepository {
    override fun findByCodes(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance> =
        codes.map { code ->
            AvoidanceSubstance.reconstitute(
                id = code.ordinal.toLong(),
                code = code,
                name = LocalizedText(
                    korean = "${code.name}-한국어",
                    translations = mapOf(LanguageCode.EN to "${code.name}-english", LanguageCode.JA to "${code.name}-日本語"),
                ),
            )
        }
}

class HomeQueryUseCaseTest : BehaviorSpec({
    fun food(id: Long, koreanName: String, substance: Pair<String, Int>? = null) = Food.reconstitute(
        id = id,
        content = FoodContent(
            name = LocalizedText(
                korean = koreanName,
                translations = mapOf(LanguageCode.EN to "$koreanName-en", LanguageCode.JA to "$koreanName-ja"),
            ),
            description = LocalizedText(korean = "설명"),
        ),
        imageRef = null,
        spiciness = FoodSpiciness(0),
        avoidanceSubstances = substance?.let {
            listOf(FoodAvoidanceSubstance(FoodAvoidanceSubstanceCodeRef(it.first), it.second))
        } ?: emptyList(),
    )

    fun member(id: Long, codes: Set<String>, appLanguage: LanguageCode?) = Member.reconstitute(
        id = id,
        identity = SocialIdentity(SocialProvider.GOOGLE, "uid-$id", null),
        profile = MemberProfile.of(
            nickname = "닉네임",
            avoidanceSubstanceCodes = codes.map { AvoidanceSubstanceCodeRef(it) }.toSet(),
            spicinessPreference = MemberProfile.DEFAULT_SPICINESS_PREFERENCE,
            countryCode = null,
            appLanguage = appLanguage,
        ),
        onboardingCompleted = true,
    )

    fun useCase(
        members: Map<Long, Member> = emptyMap(),
        readyFoods: Map<Long, Food> = emptyMap(),
        randomOrder: List<Long> = readyFoods.keys.toList(),
        recentFoodIds: List<Long> = emptyList(),
    ): HomeQueryUseCase {
        val memberRepository = HomeFakeMemberRepository(members)
        return HomeQueryUseCase(
            memberRepository = memberRepository,
            foodRepository = HomeFakeFoodRepository(readyFoods, randomOrder),
            scanHistoryRepository = HomeFakeScanHistoryRepository(recentFoodIds),
            avoidanceSubstanceRepository = HomeFakeAvoidanceSubstanceRepository(),
            avoidedSubstanceProvider = MemberAvoidedSubstanceProvider(memberRepository),
        )
    }

    given("회원의 홈 조회") {
        `when`("기피 성분·스캔 이력이 있는 회원이 조회하면") {
            then("세 섹션이 한 응답에 담긴다") {
                val uc = useCase(
                    members = mapOf(11L to member(11L, setOf("EGG"), LanguageCode.EN)),
                    readyFoods = (1L..7L).associateWith { food(it, "메뉴$it") },
                    recentFoodIds = listOf(3L, 1L),
                )

                val result = uc.getHome(11L)

                result.avoidedSubstances.map { it.code } shouldContainExactly listOf("EGG")
                result.popularFoods.size shouldBe 5
                result.recentScans.map { it.foodId } shouldContainExactly listOf(3L, 1L)
            }
        }

        `when`("프로필 언어가 일본어이면") {
            then("음식명·기피 성분명이 일본어로 지역화된다") {
                val uc = useCase(
                    members = mapOf(11L to member(11L, setOf("EGG"), LanguageCode.JA)),
                    readyFoods = mapOf(1L to food(1L, "김치찌개")),
                    recentFoodIds = listOf(1L),
                )

                val result = uc.getHome(11L)

                result.avoidedSubstances.single().name shouldBe "EGG-日本語"
                result.popularFoods.single().name shouldBe "김치찌개-ja"
                result.recentScans.single().name shouldBe "김치찌개-ja"
            }
        }

        `when`("프로필 언어가 없는(온보딩 미완료) 회원이면") {
            then("영어 기준으로 응답한다") {
                val uc = useCase(
                    members = mapOf(11L to member(11L, emptySet(), null)),
                    readyFoods = mapOf(1L to food(1L, "김치찌개")),
                )

                uc.getHome(11L).popularFoods.single().name shouldBe "김치찌개-en"
            }
        }

        `when`("기피 성분을 설정하지 않았으면") {
            then("기피 성분은 빈 목록이다") {
                val uc = useCase(
                    members = mapOf(11L to member(11L, emptySet(), LanguageCode.EN)),
                    readyFoods = mapOf(1L to food(1L, "김치찌개")),
                )

                uc.getHome(11L).avoidedSubstances shouldBe emptyList()
            }
        }

        `when`("스캔 이력이 없으면") {
            then("최근 스캔은 빈 목록이다") {
                val uc = useCase(
                    members = mapOf(11L to member(11L, setOf("EGG"), LanguageCode.EN)),
                    readyFoods = mapOf(1L to food(1L, "김치찌개")),
                    recentFoodIds = emptyList(),
                )

                uc.getHome(11L).recentScans shouldBe emptyList()
            }
        }

        `when`("최근 스캔 음식을 조회 순서와 다르게 적재해도") {
            then("스캔 최신순(포트 반환 순서)으로 정렬해 내려준다") {
                val uc = useCase(
                    members = mapOf(11L to member(11L, emptySet(), LanguageCode.EN)),
                    readyFoods = (1L..5L).associateWith { food(it, "메뉴$it") },
                    recentFoodIds = listOf(4L, 2L, 5L),
                )

                uc.getHome(11L).recentScans.map { it.foodId } shouldContainExactly listOf(4L, 2L, 5L)
            }
        }

        `when`("회원이 기피하는 성분을 100% 포함한 음식이면") {
            then("본인 프로필 기준으로 위험도를 판정한다") {
                val uc = useCase(
                    members = mapOf(11L to member(11L, setOf("EGG"), LanguageCode.EN)),
                    readyFoods = mapOf(1L to food(1L, "계란찜", "EGG" to 100)),
                )

                uc.getHome(11L).popularFoods.single().overallRiskStatus shouldBe RiskLevel.DANGER
            }
        }
    }

    given("비회원의 홈 조회") {
        `when`("memberId 없이 조회하면") {
            then("개인화 섹션은 빈 배열이고 인기 음식만 영어로 내려온다") {
                val uc = useCase(
                    readyFoods = (1L..7L).associateWith { food(it, "메뉴$it") },
                )

                val result = uc.getHome(null)

                result.avoidedSubstances shouldBe emptyList()
                result.recentScans shouldBe emptyList()
                result.popularFoods.size shouldBe 5
                result.popularFoods.first().name shouldBe "메뉴1-en"
            }
        }

        `when`("기피 성분이 없으므로") {
            then("위험도는 강조되지 않는다") {
                val uc = useCase(readyFoods = mapOf(1L to food(1L, "계란찜", "EGG" to 100)))

                uc.getHome(null).popularFoods.single().overallRiskStatus shouldBe RiskLevel.SAFE
            }
        }
    }
})
