package com.meogo.core.avoidance

import com.meogo.core.kernel.lang.LanguageCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

class AvoidanceCatalogTest : BehaviorSpec({
    val targetLanguages = listOf(
        LanguageCode.ZH_HANS,
        LanguageCode.EN,
        LanguageCode.JA,
        LanguageCode.ZH_HANT,
        LanguageCode.VI,
        LanguageCode.ID,
        LanguageCode.TH,
        LanguageCode.RU,
        LanguageCode.ES,
    )

    given("표시 명칭 해석 displayName") {
        `when`("언어가 KO 이면") {
            then("대표 성분 PEANUT 은 koName 을 반환한다") {
                AvoidanceCatalog.displayName(AvoidanceSubstance.PEANUT, LanguageCode.KO) shouldBe
                    AvoidanceSubstance.PEANUT.koName
            }
            then("모든 성분이 koName 을 반환한다") {
                AvoidanceSubstance.entries.forAll { substance ->
                    AvoidanceCatalog.displayName(substance, LanguageCode.KO) shouldBe substance.koName
                }
            }
        }

        `when`("등록된 번역 언어이면") {
            then("PEANUT 의 EN 표시 명칭은 Peanut 이다") {
                AvoidanceCatalog.displayName(AvoidanceSubstance.PEANUT, LanguageCode.EN) shouldBe "Peanut"
            }
            then("EGG 의 EN 표시 명칭은 Egg 이다") {
                AvoidanceCatalog.displayName(AvoidanceSubstance.EGG, LanguageCode.EN) shouldBe "Egg"
            }
            then("모든 성분의 모든 대상 언어 표시 명칭은 등록 번역값과 일치한다") {
                AvoidanceSubstance.entries.forAll { substance ->
                    targetLanguages.forAll { lang ->
                        AvoidanceCatalog.displayName(substance, lang) shouldBe
                            AvoidanceSubstanceTranslations.translations.getValue(substance).getValue(lang)
                    }
                }
            }
        }

        `when`("모든 성분과 모든 언어 조합을 확인하면") {
            then("표시 명칭은 절대 빈 문자열이 아니다") {
                AvoidanceSubstance.entries.forAll { substance ->
                    LanguageCode.entries.forAll { lang ->
                        AvoidanceCatalog.displayName(substance, lang).shouldNotBeBlank()
                    }
                }
            }
        }

        `when`("번역 테이블에 등록되지 않은 언어이면") {
            then("KO 는 번역 테이블에 키로 존재하지 않는다") {
                AvoidanceSubstance.entries.forAll { substance ->
                    AvoidanceSubstanceTranslations.translations.getValue(substance)
                        .shouldNotContainKey(LanguageCode.KO)
                }
            }
            then("등록되지 않은 KO 는 koName 으로 폴백된다") {
                AvoidanceSubstance.entries.forAll { substance ->
                    AvoidanceCatalog.displayName(substance, LanguageCode.KO) shouldBe substance.koName
                }
            }
        }
    }

    given("분류별 성분 조회 byCategory") {
        `when`("ALLERGEN 분류로 조회하면") {
            then("ALLERGEN 을 가진 성분만 포함하고 그 외는 제외한다") {
                val result = AvoidanceCatalog.byCategory(AvoidanceCategory.ALLERGEN)
                result.forAll { it.categories shouldContain AvoidanceCategory.ALLERGEN }
                result shouldContainAll AvoidanceSubstance.entries.filter {
                    AvoidanceCategory.ALLERGEN in it.categories
                }
            }
        }

        `when`("복수 분류 성분 PORK 의 소속을 확인하면") {
            then("세 분류 각각의 조회 결과에 모두 포함된다") {
                AvoidanceCatalog.byCategory(AvoidanceCategory.ALLERGEN) shouldContain AvoidanceSubstance.PORK
                AvoidanceCatalog.byCategory(AvoidanceCategory.DIETARY_RULE) shouldContain AvoidanceSubstance.PORK
                AvoidanceCatalog.byCategory(AvoidanceCategory.PERSONAL_AVOIDANCE) shouldContain AvoidanceSubstance.PORK
            }
        }

        `when`("성분이 속하지 않은 분류로 조회하면") {
            then("ALLERGEN 단일 성분 PEANUT 은 DIETARY_RULE 결과에 없다") {
                AvoidanceCatalog.byCategory(AvoidanceCategory.DIETARY_RULE) shouldNotContain AvoidanceSubstance.PEANUT
            }
            then("DIETARY_RULE 단일 성분 GELATIN 은 ALLERGEN 결과에 없다") {
                AvoidanceCatalog.byCategory(AvoidanceCategory.ALLERGEN) shouldNotContain AvoidanceSubstance.GELATIN
            }
        }
    }

    given("전체 성분 완전성 — 9개 대상 언어 번역 보유") {
        `when`("번역 테이블을 직접 확인하면") {
            then("모든 성분이 9개 대상 언어 키를 모두 보유한다") {
                AvoidanceSubstance.entries.forAll { substance ->
                    AvoidanceSubstanceTranslations.translations.getValue(substance).keys shouldContainAll targetLanguages
                }
            }
            then("모든 번역값은 비공백이다") {
                AvoidanceSubstance.entries.forAll { substance ->
                    targetLanguages.forAll { lang ->
                        AvoidanceSubstanceTranslations.translations.getValue(substance).getValue(lang).shouldNotBeBlank()
                    }
                }
            }
        }
    }
})
