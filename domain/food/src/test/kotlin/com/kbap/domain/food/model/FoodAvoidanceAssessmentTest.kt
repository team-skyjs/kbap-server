package com.kbap.domain.food.model

import com.kbap.core.risk.RiskLevel
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

    given("Food.assessAvoidance — 성분만 반영(맵기는 설명 작업 소관)") {
        `when`("유효한 성분 목록을 반영하면") {
            then("성분이 확률 내림차순으로 채워지고 조사완료로 판정되며 맵기는 건드리지 않는다") {
                val food = foodWith(null, spiciness = Food.SPICINESS_UNASSESSED)

                food.assessAvoidance(listOf(item("EGG", 90), item("WHEAT", 100)))

                food.needsAvoidanceMapping() shouldBe false
                food.avoidanceSubstancesByProbability() shouldBe listOf(item("WHEAT", 100), item("EGG", 90))
                food.spiciness shouldBe Food.SPICINESS_UNASSESSED
            }
        }

        `when`("빈 성분 목록을 반영하면(무성분 조사완료)") {
            then("재조사 대상이 아니게 되고 맵기는 그대로다") {
                val food = foodWith(null, spiciness = Food.SPICINESS_UNASSESSED)

                food.assessAvoidance(emptyList())

                food.needsAvoidanceMapping() shouldBe false
                food.spiciness shouldBe Food.SPICINESS_UNASSESSED
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
