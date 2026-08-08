// KB-301: 콘텐츠 채움 단계 판정·전이가 kbap-langchain 으로 이관돼 대상 로직이 비활성이다.
// 복구 가능성을 위해 원본을 주석으로 보존한다 — 최종 삭제는 KB-302.
// package com.kbap.common.domain.food.model
//
// import com.kbap.common.domain.food.model.RiskLevel
// import io.kotest.assertions.throwables.shouldThrow
// import io.kotest.core.spec.style.BehaviorSpec
// import io.kotest.matchers.shouldBe
//
// class FoodAvoidanceAssessmentTest : BehaviorSpec({
//     fun item(code: String, percent: Int) = FoodAvoidanceItem(code = code, inclusionPercent = percent)
//
//     fun foodWith(substances: List<FoodAvoidanceItem>?, spiciness: Int = 3) = Food(
//         koreanName = "된장찌개",
//         description = "구수한 된장찌개",
//         spiciness = spiciness,
//         avoidanceSubstances = substances,
//     )
//
//     given("Food.incomplete — 미조사 센티널로 생성") {
//         `when`("스캔 미스로 미완성 음식을 만들면") {
//             then("맵기는 미조사 센티널(-1)이고 기피성분은 미조사(null)다") {
//                 val food = Food.incomplete("우주라면")
//
//                 food.spiciness shouldBe Food.SPICINESS_UNASSESSED
//                 Food.SPICINESS_UNASSESSED shouldBe -1
//                 food.avoidanceSubstances shouldBe null
//             }
//         }
//     }
//
//     given("Food.needsAvoidanceMapping — null(미조사)만 재조사 대상") {
//         `when`("기피성분이 null·빈 목록·비어있지 않음 세 상태이면") {
//             then("null 만 true 고 빈 목록(조사완료·무성분)·비어있지 않음은 false 다") {
//                 foodWith(null).needsAvoidanceMapping() shouldBe true
//                 foodWith(emptyList()).needsAvoidanceMapping() shouldBe false
//                 foodWith(listOf(item("SOYBEAN", 100))).needsAvoidanceMapping() shouldBe false
//             }
//         }
//     }
//
//     given("Food.assessAvoidance — 성분과 맵기를 함께 반영(다중 모델 종합 결과)") {
//         `when`("유효한 성분 목록과 맵기를 반영하면") {
//             then("성분이 확률 내림차순으로 채워지고 맵기도 함께 설정된다") {
//                 val food = foodWith(null, spiciness = Food.SPICINESS_UNASSESSED)
//
//                 food.assessAvoidance(listOf(item("EGG", 90), item("WHEAT", 100)), 4)
//
//                 food.needsAvoidanceMapping() shouldBe false
//                 food.avoidanceSubstancesByProbability() shouldBe listOf(item("WHEAT", 100), item("EGG", 90))
//                 food.spiciness shouldBe 4
//             }
//         }
//
//         `when`("빈 성분 목록과 맵기 0 을 반영하면(무성분·안 매움 조사완료)") {
//             then("재조사 대상이 아니게 되고 맵기는 0 이다") {
//                 val food = foodWith(null, spiciness = Food.SPICINESS_UNASSESSED)
//
//                 food.assessAvoidance(emptyList(), 0)
//
//                 food.needsAvoidanceMapping() shouldBe false
//                 food.needsAvoidanceAssessment() shouldBe false
//                 food.spiciness shouldBe 0
//             }
//         }
//
//         `when`("범위 밖 맵기(-1)를 반영하려 하면") {
//             then("예외가 발생하고 상태는 변하지 않는다") {
//                 val food = foodWith(null, spiciness = Food.SPICINESS_UNASSESSED)
//
//                 shouldThrow<IllegalArgumentException> { food.assessAvoidance(emptyList(), -1) }
//
//                 food.avoidanceSubstances shouldBe null
//             }
//         }
//
//         `when`("범위 밖 맵기(11)를 반영하려 하면") {
//             then("예외가 발생한다") {
//                 shouldThrow<IllegalArgumentException> {
//                     foodWith(null).assessAvoidance(emptyList(), 11)
//                 }
//             }
//         }
//     }
//
//     given("Food.needsAvoidanceAssessment — 성분·맵기 중 하나라도 미완이면 재판정 대상") {
//         `when`("기피성분이 미조사(null)이면") {
//             then("맵기가 채워져 있어도 true 다") {
//                 foodWith(null, spiciness = 3).needsAvoidanceAssessment() shouldBe true
//             }
//         }
//
//         `when`("성분은 조사완료인데 맵기가 미판정(-1)이면") {
//             then("true 다") {
//                 foodWith(listOf(item("SOYBEAN", 100)), spiciness = Food.SPICINESS_UNASSESSED)
//                     .needsAvoidanceAssessment() shouldBe true
//             }
//         }
//
//         `when`("성분·맵기 둘 다 채워졌으면") {
//             then("false 다") {
//                 foodWith(emptyList(), spiciness = 0).needsAvoidanceAssessment() shouldBe false
//                 foodWith(listOf(item("SOYBEAN", 100)), spiciness = 3).needsAvoidanceAssessment() shouldBe false
//             }
//         }
//     }
//
//     given("Food 파생 메서드 — 미조사(null) 상태 null-safe") {
//         `when`("기피성분이 null 인 음식의 확률 정렬 목록을 물으면") {
//             then("NPE 없이 빈 목록을 반환한다") {
//                 foodWith(null).avoidanceSubstancesByProbability() shouldBe emptyList()
//             }
//         }
//
//         `when`("기피성분이 null 인 미완성 음식의 위험도를 물으면") {
//             then("NPE 없이 UNKNOWN 을 반환한다") {
//                 Food.incomplete("우주라면").overallRisk(emptySet()) shouldBe RiskLevel.UNKNOWN
//             }
//         }
//     }
// })
