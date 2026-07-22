package com.kbap.domain.food.model

import com.kbap.core.risk.RiskLevel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodAvoidanceAssessmentTest : BehaviorSpec({
    fun item(code: String, percent: Int) = FoodAvoidanceItem(code = code, inclusionPercent = percent)

    fun foodWith(substances: List<FoodAvoidanceItem>?, spiciness: Int = 3) = Food(
        koreanName = "된장찌개",
        description = "구수한 된장찌개",
        spiciness = spiciness,
        avoidanceSubstances = substances,
    )

    given("Food.incomplete — 미조사 센티널로 생성") {
        `when`("스캔 미스로 미완성 음식을 만들면") {
            then("맵기는 미조사 센티널(-1)이고 기피성분은 미조사(null)다") {
                val food = Food.incomplete("우주라면")

                food.spiciness shouldBe Food.SPICINESS_UNASSESSED
                Food.SPICINESS_UNASSESSED shouldBe -1
                food.avoidanceSubstances shouldBe null
            }
        }
    }

    given("Food.needsAvoidanceMapping — null(미조사)만 재조사 대상") {
        `when`("기피성분이 null·빈 목록·비어있지 않음 세 상태이면") {
            then("null 만 true 고 빈 목록(조사완료·무성분)·비어있지 않음은 false 다") {
                foodWith(null).needsAvoidanceMapping() shouldBe true
                foodWith(emptyList()).needsAvoidanceMapping() shouldBe false
                foodWith(listOf(item("SOYBEAN", 100))).needsAvoidanceMapping() shouldBe false
            }
        }
    }

    given("Food.assessAvoidance — 성분·맵기 원자 반영") {
        `when`("유효한 성분 목록과 맵기(0~10)를 반영하면") {
            then("성분과 맵기가 함께 채워지고 조사완료로 판정된다") {
                val food = foodWith(null, spiciness = Food.SPICINESS_UNASSESSED)

                food.assessAvoidance(listOf(item("EGG", 90), item("WHEAT", 100)), spiciness = 7)

                food.spiciness shouldBe 7
                food.needsAvoidanceMapping() shouldBe false
                food.avoidanceSubstancesByProbability() shouldBe listOf(item("WHEAT", 100), item("EGG", 90))
            }
        }

        `when`("빈 성분 목록을 반영하면(무성분 조사완료)") {
            then("맵기는 채워지고 무한 재조사 대상이 아니게 된다") {
                val food = foodWith(null, spiciness = Food.SPICINESS_UNASSESSED)

                food.assessAvoidance(emptyList(), spiciness = 0)

                food.spiciness shouldBe 0
                food.needsAvoidanceMapping() shouldBe false
            }
        }

        `when`("맵기가 0 미만이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    foodWith(null).assessAvoidance(listOf(item("EGG", 90)), spiciness = -1)
                }
            }
        }

        `when`("맵기가 10 을 넘으면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    foodWith(null).assessAvoidance(listOf(item("EGG", 90)), spiciness = 11)
                }
            }
        }

        `when`("맵기가 경계값(하한 0·상한 10)이면") {
            then("둘 다 유효하게 반영된다") {
                foodWith(null).apply { assessAvoidance(listOf(item("EGG", 90)), spiciness = 0) }
                    .spiciness shouldBe 0
                foodWith(null).apply { assessAvoidance(listOf(item("EGG", 90)), spiciness = 10) }
                    .spiciness shouldBe 10
            }
        }
    }

    given("Food 파생 메서드 — 미조사(null) 상태 null-safe") {
        `when`("기피성분이 null 인 음식의 확률 정렬 목록을 물으면") {
            then("NPE 없이 빈 목록을 반환한다") {
                foodWith(null).avoidanceSubstancesByProbability() shouldBe emptyList()
            }
        }

        `when`("기피성분이 null 인 미완성 음식의 위험도를 물으면") {
            then("NPE 없이 UNKNOWN 을 반환한다") {
                Food.incomplete("우주라면").overallRisk(emptySet()) shouldBe RiskLevel.UNKNOWN
            }
        }
    }
})
