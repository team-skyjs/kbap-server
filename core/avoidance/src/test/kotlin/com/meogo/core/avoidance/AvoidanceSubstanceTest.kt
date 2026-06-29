package com.meogo.core.avoidance

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

class AvoidanceSubstanceTest : BehaviorSpec({
    given("회피·주의 성분 enum 카탈로그") {
        `when`("전체 성분을 조회하면") {
            then("정확히 81종이다") {
                AvoidanceSubstance.entries.size shouldBe 81
            }
            then("성분 코드는 모두 유일하다") {
                AvoidanceSubstance.entries.map { it.name }.distinct().size shouldBe 81
            }
        }

        `when`("각 성분의 분류를 확인하면") {
            then("분류 개수는 1개 이상 3개 이하다") {
                AvoidanceSubstance.entries.forAll { substance ->
                    substance.categories.size shouldBeInRange 1..3
                }
            }
        }

        `when`("각 성분의 ko 명칭을 확인하면") {
            then("모두 비공백이다") {
                AvoidanceSubstance.entries.forAll { substance ->
                    substance.koName.shouldNotBeBlank()
                }
            }
        }

        `when`("대표 성분 PEANUT 과 PORK 를 확인하면") {
            then("PEANUT 은 ALLERGEN 단일 분류에 ko 가 땅콩이다") {
                AvoidanceSubstance.PEANUT.koName shouldBe "땅콩"
                AvoidanceSubstance.PEANUT.categories shouldBe setOf(AvoidanceCategory.ALLERGEN)
            }
            then("PORK 는 세 분류를 모두 갖고 ko 가 돼지고기다") {
                AvoidanceSubstance.PORK.koName shouldBe "돼지고기"
                AvoidanceSubstance.PORK.categories shouldBe setOf(
                    AvoidanceCategory.ALLERGEN,
                    AvoidanceCategory.DIETARY_RULE,
                    AvoidanceCategory.PERSONAL_AVOIDANCE,
                )
            }
        }
    }

    given("회피·주의 분류 enum") {
        `when`("전체 분류를 조회하면") {
            then("정확히 세 값 ALLERGEN/DIETARY_RULE/PERSONAL_AVOIDANCE 이다") {
                AvoidanceCategory.entries shouldContainExactly listOf(
                    AvoidanceCategory.ALLERGEN,
                    AvoidanceCategory.DIETARY_RULE,
                    AvoidanceCategory.PERSONAL_AVOIDANCE,
                )
            }
        }
    }
})
