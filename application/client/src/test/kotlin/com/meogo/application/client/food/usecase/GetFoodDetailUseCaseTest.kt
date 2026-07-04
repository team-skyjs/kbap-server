package com.meogo.application.client.food.usecase

import com.meogo.application.client.food.dto.GetFoodDetailInput
import com.meogo.core.avoidance.AvoidanceSubstance
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.avoidance.AvoidanceSubstanceRepository
import com.meogo.core.food.AvoidanceSubstanceCodeRef
import com.meogo.core.food.Food
import com.meogo.core.food.FoodAvoidanceSubstance
import com.meogo.core.food.FoodContent
import com.meogo.core.food.FoodException
import com.meogo.core.food.FoodRepository
import com.meogo.core.food.FoodSpiciness
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.lang.LanguageException
import com.meogo.core.kernel.lang.LocalizedText
import com.meogo.core.kernel.risk.RiskLevel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class GetFoodDetailUseCaseTest : BehaviorSpec({
    val koDescription = "구수한 된장찌개"

    fun doenjangStew(
        nameTranslations: Map<LanguageCode, String> = emptyMap(),
        descriptionTranslations: Map<LanguageCode, String> = emptyMap(),
    ) = Food.reconstitute(
        id = 1,
        content = FoodContent(
            name = LocalizedText(korean = "된장찌개", translations = nameTranslations),
            description = LocalizedText(korean = koDescription, translations = descriptionTranslations),
        ),
        imageRef = "doenjang.png",
        spiciness = FoodSpiciness(3),
        avoidanceSubstances = listOf(
            FoodAvoidanceSubstance(substanceCode = AvoidanceSubstanceCodeRef("WHEAT"), inclusionProbability = 80),
            FoodAvoidanceSubstance(substanceCode = AvoidanceSubstanceCodeRef("SOY"), inclusionProbability = 100),
        ),
    )

    fun substance(code: AvoidanceSubstanceCode, koreanName: String, translations: Map<LanguageCode, String> = emptyMap()) =
        AvoidanceSubstance.reconstitute(
            id = code.ordinal.toLong() + 1,
            code = code,
            name = LocalizedText(korean = koreanName, translations = translations),
        )

    val soy = substance(AvoidanceSubstanceCode.SOY, "대두", mapOf(LanguageCode.EN to "Soybean"))
    val wheat = substance(AvoidanceSubstanceCode.WHEAT, "밀", mapOf(LanguageCode.EN to "Wheat"))

    fun useCase(
        foodRepository: FoodRepository,
        avoidanceSubstanceRepository: AvoidanceSubstanceRepository,
    ) = GetFoodDetailUseCase(
        foodRepository,
        avoidanceSubstanceRepository,
        LanguageResolver(),
        MockAvoidanceRiskMarker(),
    )

    given("음식 상세 조회 유스케이스 — 포함 기피 성분 표시명·확률·위험도 조립") {
        `when`("요청 언어 성분 번역이 모두 있으면") {
            then("성분 표시명을 요청 언어로 조립하고 iconRef 는 null, 확률 내림차순·최상위 CAUTION 을 부여한다") {
                val foodRepository = FakeFoodRepository(
                    food = doenjangStew(nameTranslations = mapOf(LanguageCode.EN to "Doenjang Stew")),
                )
                val avoidanceRepository = FakeAvoidanceSubstanceRepository(listOf(soy, wheat))

                val result = useCase(foodRepository, avoidanceRepository).getDetail(GetFoodDetailInput("된장찌개", "en"))

                result.name shouldBe "Doenjang Stew"
                result.imageRef shouldBe "doenjang.png"
                result.spiciness shouldBe 3
                result.avoidanceSubstances.map { it.name } shouldBe listOf("Soybean", "Wheat")
                result.avoidanceSubstances.map { it.inclusionProbability } shouldBe listOf(100, 80)
                result.avoidanceSubstances.map { it.riskStatus } shouldBe listOf(RiskLevel.CAUTION, RiskLevel.SAFE)
                result.avoidanceSubstances.map { it.iconRef } shouldBe listOf(null, null)
            }
        }

        `when`("포함 기피 성분이 확률 내림차순이 아닌 순서로 저장돼 있으면") {
            then("응답 성분을 확률 내림차순으로 정렬하고 최상위에 CAUTION 을 부여한다") {
                val foodRepository = FakeFoodRepository(food = doenjangStew())
                val avoidanceRepository = FakeAvoidanceSubstanceRepository(listOf(soy, wheat))

                val result = useCase(foodRepository, avoidanceRepository).getDetail(GetFoodDetailInput("된장찌개", "ko"))

                result.avoidanceSubstances.map { it.inclusionProbability } shouldBe listOf(100, 80)
                result.avoidanceSubstances.map { it.name } shouldBe listOf("대두", "밀")
                result.avoidanceSubstances.map { it.riskStatus } shouldBe listOf(RiskLevel.CAUTION, RiskLevel.SAFE)
            }
        }

        `when`("성분 코드 문자열로 카탈로그를 조회하면") {
            then("substanceCode 를 AvoidanceSubstanceCode 로 변환해 findByCodes 에 전달한다") {
                val foodRepository = FakeFoodRepository(food = doenjangStew())
                val avoidanceRepository = FakeAvoidanceSubstanceRepository(listOf(soy, wheat))

                useCase(foodRepository, avoidanceRepository).getDetail(GetFoodDetailInput("된장찌개", "ko"))

                avoidanceRepository.requestedCodes shouldContainExactlyInAnyOrder
                    listOf(AvoidanceSubstanceCode.SOY, AvoidanceSubstanceCode.WHEAT)
            }
        }

        `when`("요청 언어 번역이 없는 성분이 섞여 있으면") {
            then("번역 없는 성분 표시명만 한국어로 폴백한다") {
                val foodRepository = FakeFoodRepository(food = doenjangStew())
                val wheatNoEn = substance(AvoidanceSubstanceCode.WHEAT, "밀")
                val avoidanceRepository = FakeAvoidanceSubstanceRepository(listOf(soy, wheatNoEn))

                val result = useCase(foodRepository, avoidanceRepository).getDetail(GetFoodDetailInput("된장찌개", "en"))

                result.avoidanceSubstances.map { it.name } shouldBe listOf("Soybean", "밀")
            }
        }

        `when`("포함 기피 성분이 하나도 없으면") {
            then("성분 목록이 빈 채로 정상 조립된다") {
                val plainRice = Food.reconstitute(
                    id = 2,
                    content = FoodContent(
                        name = LocalizedText(korean = "흰밥"),
                        description = LocalizedText(korean = "흰밥은 쌀로 지은 밥이다."),
                    ),
                    imageRef = null,
                    spiciness = FoodSpiciness(0),
                    avoidanceSubstances = emptyList(),
                )
                val foodRepository = FakeFoodRepository(food = plainRice)
                val avoidanceRepository = FakeAvoidanceSubstanceRepository(emptyList())

                val result = useCase(foodRepository, avoidanceRepository).getDetail(GetFoodDetailInput("흰밥", "ko"))

                result.avoidanceSubstances shouldBe emptyList()
                result.spiciness shouldBe 0
            }
        }

        `when`("미지원 lang 이면") {
            then("LanguageException 을 던진다") {
                val foodRepository = FakeFoodRepository(food = doenjangStew())
                val avoidanceRepository = FakeAvoidanceSubstanceRepository(listOf(soy, wheat))

                shouldThrow<LanguageException> {
                    useCase(foodRepository, avoidanceRepository).getDetail(GetFoodDetailInput("된장찌개", "xx"))
                }
            }
        }

        `when`("수록되지 않은 메뉴명이면") {
            then("FoodException(\"해당 음식 정보 없음\") 을 던진다") {
                val foodRepository = FakeFoodRepository(food = null)
                val avoidanceRepository = FakeAvoidanceSubstanceRepository(emptyList())

                shouldThrow<FoodException> {
                    useCase(foodRepository, avoidanceRepository).getDetail(GetFoodDetailInput("없는메뉴", "en"))
                }.message shouldBe "해당 음식 정보 없음"
            }
        }
    }

    given("음식 상세 조회 유스케이스 — 단일 설명 다국어·폴백") {
        `when`("요청 언어로 설명 번역이 있으면") {
            then("설명을 요청 언어로 조립한다") {
                val foodRepository = FakeFoodRepository(
                    food = doenjangStew(
                        descriptionTranslations = mapOf(LanguageCode.EN to "A hearty Korean soybean paste stew."),
                    ),
                )
                val avoidanceRepository = FakeAvoidanceSubstanceRepository(listOf(soy, wheat))

                val result = useCase(foodRepository, avoidanceRepository).getDetail(GetFoodDetailInput("된장찌개", "en"))

                result.description shouldBe "A hearty Korean soybean paste stew."
            }
        }

        `when`("lang=ko 이면") {
            then("음식명·설명을 모두 한국어 원문으로 채운다") {
                val foodRepository = FakeFoodRepository(
                    food = doenjangStew(
                        nameTranslations = mapOf(LanguageCode.EN to "Doenjang Stew"),
                        descriptionTranslations = mapOf(LanguageCode.EN to "A hearty Korean soybean paste stew."),
                    ),
                )
                val avoidanceRepository = FakeAvoidanceSubstanceRepository(listOf(soy, wheat))

                val result = useCase(foodRepository, avoidanceRepository).getDetail(GetFoodDetailInput("된장찌개", "ko"))

                result.name shouldBe "된장찌개"
                result.description shouldBe koDescription
            }
        }

        `when`("요청 언어 설명 번역이 없으면") {
            then("설명만 한국어로 폴백하고 음식명은 요청 언어를 유지한다") {
                val foodRepository = FakeFoodRepository(
                    food = doenjangStew(nameTranslations = mapOf(LanguageCode.EN to "Doenjang Stew")),
                )
                val avoidanceRepository = FakeAvoidanceSubstanceRepository(listOf(soy, wheat))

                val result = useCase(foodRepository, avoidanceRepository).getDetail(GetFoodDetailInput("된장찌개", "en"))

                result.description shouldBe koDescription
                result.name shouldBe "Doenjang Stew"
            }
        }
    }
})

private class FakeFoodRepository(
    private val food: Food?,
) : FoodRepository {
    override fun findByKoreanName(name: String): Food? = food
}

private class FakeAvoidanceSubstanceRepository(
    private val substances: List<AvoidanceSubstance>,
) : AvoidanceSubstanceRepository {
    var requestedCodes: Set<AvoidanceSubstanceCode> = emptySet()
        private set

    override fun findByCodes(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance> {
        requestedCodes = codes
        return substances.filter { it.code in codes }
    }
}
