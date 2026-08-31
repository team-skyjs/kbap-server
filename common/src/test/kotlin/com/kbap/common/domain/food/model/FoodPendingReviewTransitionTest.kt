// KB-301: 콘텐츠 채움 단계 판정·전이가 kbap-langchain 으로 이관돼 대상 로직이 비활성이다.
// 복구 가능성을 위해 원본을 주석으로 보존한다 — 최종 삭제는 KB-302.
// package com.kbap.common.domain.food.model
//
// import com.kbap.common.domain.LanguageCode
// import io.kotest.core.spec.style.BehaviorSpec
// import io.kotest.matchers.shouldBe
//
// class FoodPendingReviewTransitionTest : BehaviorSpec({
//     val targetLangs = LanguageCode.entries.filter { it != LanguageCode.KO }.map { it.code }
//
//     fun allTargets(value: String) = targetLangs.associateWith { "$value-$it" }
//
//     fun incomplete(
//         imageRef: String? = "images/food/1.png",
//         description: String = "구수한 된장찌개",
//         nameTranslations: Map<String, String> = allTargets("된장찌개"),
//         descriptionTranslations: Map<String, String> = allTargets("hearty stew"),
//         avoidanceSubstances: List<FoodAvoidanceItem>? = listOf(FoodAvoidanceItem("SOYBEAN", 100)),
//         spiciness: Int = 3,
//     ) = Food(
//         koreanName = "된장찌개",
//         imageRef = imageRef,
//         description = description,
//         spiciness = spiciness,
//         nameTranslations = nameTranslations,
//         descriptionTranslations = descriptionTranslations,
//         avoidanceSubstances = avoidanceSubstances,
//         contentStatus = FoodContentStatus.INCOMPLETE,
//     )
//
//     given("Food.transitionByContentState — 수렴표: 텍스트 완료 × 이미지 유무") {
//         `when`("텍스트 4작업이 완료되고 이미지도 있으면") {
//             then("PENDING_IMAGE 를 건너뛰고 곧장 PENDING_REVIEW 로 전이한다") {
//                 val food = incomplete()
//
//                 food.transitionByContentState() shouldBe FoodContentStatus.PENDING_REVIEW
//                 food.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
//             }
//         }
//
//         `when`("텍스트 4작업이 완료됐지만 이미지가 없으면") {
//             then("이미지 대기실 PENDING_IMAGE 로 전이한다") {
//                 val food = incomplete(imageRef = null)
//
//                 food.transitionByContentState() shouldBe FoodContentStatus.PENDING_IMAGE
//                 food.contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
//             }
//         }
//
//         `when`("텍스트가 미완이면 이미지가 먼저 도착해 있어도") {
//             then("INCOMPLETE 를 유지한다 — 이미지는 imageRef 로만 보관") {
//                 val food = incomplete(description = "")
//
//                 food.transitionByContentState() shouldBe FoodContentStatus.INCOMPLETE
//                 food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
//                 food.imageRef shouldBe "images/food/1.png"
//             }
//         }
//
//         `when`("이미지가 blank 문자열이면") {
//             then("이미지 없음으로 보고 PENDING_IMAGE 로 전이한다") {
//                 val food = incomplete(imageRef = "  ")
//
//                 food.transitionByContentState() shouldBe FoodContentStatus.PENDING_IMAGE
//             }
//         }
//     }
//
//     given("Food.transitionByContentState — 텍스트 미완 게이트") {
//         `when`("description 이 placeholder(설명 준비 중) 그대로이면") {
//             then("INCOMPLETE 를 유지한다") {
//                 val food = incomplete(description = Food.PLACEHOLDER_DESCRIPTION)
//
//                 food.transitionByContentState() shouldBe FoodContentStatus.INCOMPLETE
//             }
//         }
//
//         `when`("이름 번역이 9개 대상 언어 중 하나(ja)를 빠뜨리면") {
//             then("INCOMPLETE 를 유지한다") {
//                 val food = incomplete(nameTranslations = allTargets("된장찌개") - LanguageCode.JA.code)
//
//                 food.transitionByContentState() shouldBe FoodContentStatus.INCOMPLETE
//             }
//         }
//
//         `when`("설명 번역에 9개 키가 다 있어도 값 하나(en)가 blank 이면") {
//             then("INCOMPLETE 를 유지한다") {
//                 val food = incomplete(
//                     descriptionTranslations = allTargets("hearty stew") + (LanguageCode.EN.code to ""),
//                 )
//
//                 food.transitionByContentState() shouldBe FoodContentStatus.INCOMPLETE
//             }
//         }
//
//         `when`("기피성분이 미조사(null)이면") {
//             then("안전 직결이라 INCOMPLETE 를 유지한다") {
//                 val food = incomplete(avoidanceSubstances = null)
//
//                 food.transitionByContentState() shouldBe FoodContentStatus.INCOMPLETE
//             }
//         }
//
//         `when`("맵기가 미조사 센티널(-1)이면") {
//             then("INCOMPLETE 를 유지한다") {
//                 val food = incomplete(spiciness = Food.SPICINESS_UNASSESSED)
//
//                 food.transitionByContentState() shouldBe FoodContentStatus.INCOMPLETE
//             }
//         }
//
//         `when`("기피성분이 빈 목록(무성분 조사완료)이고 맵기 0이면") {
//             then("조사완료로 보고 전이한다") {
//                 val food = incomplete(avoidanceSubstances = emptyList(), spiciness = 0)
//
//                 food.transitionByContentState() shouldBe FoodContentStatus.PENDING_REVIEW
//             }
//         }
//     }
//
//     given("Food.transitionByContentState — 검수 이후 상태는 재평가하지 않음") {
//         `when`("이미 검수 대기(PENDING_REVIEW)인 음식에 다시 전이를 시도하면") {
//             then("상태 불변으로 PENDING_REVIEW 를 유지한다") {
//                 val food = incomplete()
//                 food.transitionByContentState()
//
//                 food.transitionByContentState() shouldBe FoodContentStatus.PENDING_REVIEW
//                 food.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
//             }
//         }
//
//         `when`("이미 승인된(READY) 음식에 전이를 시도하면") {
//             then("READY 를 유지한다") {
//                 val food = incomplete()
//                 food.contentStatus = FoodContentStatus.READY
//
//                 food.transitionByContentState() shouldBe FoodContentStatus.READY
//             }
//         }
//     }
//
//     given("Food.attachImage — 이미지 회수의 진입점") {
//         `when`("PENDING_IMAGE(이미지만 대기) 음식에 이미지를 붙이면") {
//             then("imageRef 저장과 함께 PENDING_REVIEW 로 전이한다") {
//                 val food = incomplete(imageRef = null)
//                 food.transitionByContentState() shouldBe FoodContentStatus.PENDING_IMAGE
//
//                 food.attachImage("images/food/7.png")
//
//                 food.imageRef shouldBe "images/food/7.png"
//                 food.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
//             }
//         }
//
//         `when`("텍스트 미완(INCOMPLETE) 음식에 이미지가 먼저 도착하면") {
//             then("imageRef 만 저장하고 INCOMPLETE 를 유지한다 — 이후 텍스트 완료 시 배치가 PENDING_REVIEW 로 민다") {
//                 val food = incomplete(imageRef = null, description = "")
//
//                 food.attachImage("images/food/8.png")
//
//                 food.imageRef shouldBe "images/food/8.png"
//                 food.contentStatus shouldBe FoodContentStatus.INCOMPLETE
//
//                 food.description = "늦게 채워진 설명"
//                 food.transitionByContentState() shouldBe FoodContentStatus.PENDING_REVIEW
//             }
//         }
//     }
//
//     given("Food.needsX — 작업별 완료 여부(배치 skip 근거)") {
//         `when`("모든 콘텐츠가 채워진 음식이면") {
//             then("네 작업 모두 needs=false 다") {
//                 val food = incomplete()
//
//                 food.needsImage() shouldBe false
//                 food.needsDescription() shouldBe false
//                 food.needsNameTranslations() shouldBe false
//                 food.needsDescriptionTranslations() shouldBe false
//                 food.needsAvoidanceMapping() shouldBe false
//             }
//         }
//
//         `when`("이미지가 비어 있으면") {
//             then("needsImage 만 true 다") {
//                 val food = incomplete(imageRef = null)
//
//                 food.needsImage() shouldBe true
//                 food.needsDescription() shouldBe false
//             }
//         }
//     }
// })
