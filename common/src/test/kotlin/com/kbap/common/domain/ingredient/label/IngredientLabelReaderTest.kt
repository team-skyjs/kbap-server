package com.kbap.common.domain.ingredient.label

import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.domain.ingredient.model.IngredientCode.ABALONE
import com.kbap.common.domain.ingredient.model.IngredientCode.BUTTER
import com.kbap.common.domain.ingredient.model.IngredientCode.CLAM
import com.kbap.common.domain.ingredient.model.IngredientCode.EGG
import com.kbap.common.domain.ingredient.model.IngredientCode.MILK
import com.kbap.common.domain.ingredient.model.IngredientCode.MUSSEL
import com.kbap.common.domain.ingredient.model.IngredientCode.OYSTER
import com.kbap.common.domain.ingredient.model.IngredientCode.PEANUT
import com.kbap.common.domain.ingredient.model.IngredientCode.RYE
import com.kbap.common.domain.ingredient.model.IngredientCode.SHRIMP
import com.kbap.common.domain.ingredient.model.IngredientCode.SOY
import com.kbap.common.domain.ingredient.model.IngredientCode.WHEAT
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class IngredientLabelReaderTest : BehaviorSpec({
    fun codes(reading: IngredientLabelReading): Set<IngredientCode> = reading.detections.map { it.code }.toSet()

    given("알레르기 유발물질 표시(의무 표시) 원문") {
        `when`("쉼표 목록이면") {
            then("각 품목을 긍정 검출하고 출처는 알레르기 표시다") {
                val reading = IngredientLabelReader.read(allergens = "우유,대두,밀,쇠고기", rawMaterials = null)
                codes(reading) shouldBe setOf(MILK, SOY, WHEAT, IngredientCode.BEEF)
                reading.detections.map { it.source }.toSet() shouldBe setOf(IngredientLabelSource.ALLERGEN_DECLARATION)
                reading.declaration shouldBe AllergenDeclaration.LISTED
            }
        }
        `when`("괄호가 다음 항목까지 이어지거나 붙어 있으면") {
            then("괄호 안 품목까지 모두 찾는다 — 조개류(굴, 전복, 홍합 포함)·유청(우유)난각칼슘(계란)·대두[d-토코페롤(혼합형)]") {
                codes(IngredientLabelReader.read("조개류(굴, 전복, 홍합 포함)", null)) shouldBe setOf(CLAM, OYSTER, ABALONE, MUSSEL)
                codes(IngredientLabelReader.read("유청(우유)난각칼슘(계란)", null)) shouldBe setOf(MILK, EGG)
                codes(IngredientLabelReader.read("대두[d-토코페롤(혼합형)]", null)) shouldBe setOf(SOY)
            }
        }
        `when`("구성품별로 '이름 : 품목' 이 이어 붙어 있으면") {
            then("품목만 찾는다") {
                codes(IngredientLabelReader.read("현미유과 : 대두 흑미유과 : 대두 교동약과 : 밀, 우유 함유", null)) shouldBe setOf(SOY, WHEAT, MILK)
            }
        }
        `when`("고시 표기 그대로 '알류(가금류에 한함)'이면 — Codex P1") {
            then("알만 검출하고 가금류(고기)는 검출하지 않는다 — 한정어일 뿐이다") {
                codes(IngredientLabelReader.read("알류(가금류에 한함), 우유", null)) shouldBe setOf(EGG, MILK)
                codes(IngredientLabelReader.read(null, "전란액(가금류에 한한다)")) shouldBe setOf(EGG)
            }
        }
        `when`("'알수없음'·빈 값이면") {
            then("표시 없음(NOT_PROVIDED)이고 원재료에서만 찾는다") {
                listOf("알수없음", "", "  ", null).forEach { allergens ->
                    withClue(allergens ?: "null") {
                        val reading = IngredientLabelReader.read(allergens, "소맥분(밀:미국산), 정제수")
                        reading.declaration shouldBe AllergenDeclaration.NOT_PROVIDED
                        codes(reading) shouldBe setOf(WHEAT)
                        reading.detections.single().source shouldBe IngredientLabelSource.RAW_MATERIALS
                    }
                }
            }
        }
        `when`("'해당없음'이면") {
            then("표시는 있으나(LISTED) 검출은 없다 — 원재료 검출은 그대로 더한다") {
                val reading = IngredientLabelReader.read("해당없음", "땅콩, 설탕")
                reading.declaration shouldBe AllergenDeclaration.LISTED
                codes(reading) shouldBe setOf(PEANUT)
            }
        }
    }

    given("원재료 원문") {
        `when`("비율·원산지·중괄호 복합원재료가 섞여 있으면") {
            then("하위 원재료까지 찾는다") {
                val reading = IngredientLabelReader.read(
                    null,
                    "옥수수(미국산)50.0%, 가공버터{유지방(우유), 코코넛오일}, 치즈믹스파우더{버터리체다시즈닝(대두, 우유), 치즈풍미부스터(새우,밀)}",
                )
                codes(reading) shouldContainAll setOf(IngredientCode.CORN, BUTTER, MILK, SOY, SHRIMP, WHEAT, IngredientCode.CHEESE)
            }
        }
        `when`("이름에 알레르기 품목 글자가 들어 있지만 그 재료가 아니면") {
            then("긍정 검출하지 않고 미매핑 원문으로 남긴다 — 코코아버터·버터향·복숭아향·돼지감자·호밀·들깨·대구산·밀감") {
                val reading = IngredientLabelReader.read(null, "코코아버터, 버터향, 복숭아향, 돼지감자, 호밀, 들깨, 사과(대구산), 밀감")
                codes(reading) shouldBe setOf(RYE)
                reading.unmappedTokens shouldContainAll listOf("코코아버터", "버터향", "복숭아향", "돼지감자", "들깨", "대구산", "밀감")
            }
        }
        `when`("토큰이 맛·향·풍미로 끝나는 향료 표기이면") {
            then("그 품목으로 검출하지 않는다 — 버터향·우유맛·복숭아향·딸기풍미") {
                codes(IngredientLabelReader.read(null, "합성착향료(버터향, 우유맛), 복숭아향, 딸기풍미")) shouldBe emptySet()
            }
        }
        `when`("맛·풍미 뒤에 분말·오일·유·베이스·시즈닝이 붙은 원료이면 — 놓침이 오탐보다 비싸다(종교 회피)") {
            then("그 품목으로 검출한다") {
                codes(IngredientLabelReader.read(null, "돈육풍미분말, 소고기풍미분말, 쇠고기맛분말, 새우맛분말, 마늘맛오일, 땅콩향미유, 닭고기맛분말")) shouldBe
                    setOf(IngredientCode.PORK, IngredientCode.BEEF, SHRIMP, IngredientCode.GARLIC, PEANUT, IngredientCode.CHICKEN)
            }
        }
        `when`("가운뎃점 변형·괄호 변형·일본식 쉼표로 이어 쓰면") {
            then("구분자로 보고 짧은 품목(밀·굴·게)까지 찾는다 — 흔적 없는 누락 금지") {
                codes(IngredientLabelReader.read("밀∙대두", null)) shouldBe setOf(WHEAT, SOY)
                codes(IngredientLabelReader.read("굴ㆍ전복‧홍합、게", null)) shouldBe setOf(OYSTER, ABALONE, MUSSEL, IngredientCode.CRAB)
                codes(IngredientLabelReader.read("【밀】", null)) shouldBe setOf(WHEAT)
                codes(IngredientLabelReader.read(null, "소맥분∙우유∙정제소금")) shouldBe setOf(WHEAT, MILK)
                codes(IngredientLabelReader.read("대두,밀.우유", null)) shouldBe setOf(SOY, WHEAT, MILK)
                codes(IngredientLabelReader.read("밀ㅡ대두ㅣ우유ー계란ｰ새우", null)) shouldBe setOf(WHEAT, SOY, MILK, EGG, SHRIMP)
                codes(IngredientLabelReader.read(null, "밀가루 2.5g, 우유.")) shouldBe setOf(WHEAT, MILK)
            }
        }
        `when`("땅콩기름·땅콩유이면") {
            then("땅콩만 검출한다 — 콩기름(대두)으로 잡지 않는다") {
                codes(IngredientLabelReader.read(null, "땅콩기름, 땅콩유")) shouldBe setOf(PEANUT)
            }
        }
        `when`("돈지방이 원재료에 있으면 — Codex P2") {
            then("라드(돼지 지방)로 검출한다") {
                codes(IngredientLabelReader.read(null, "돼지창자, 돈지방, 당면")) shouldContain IngredientCode.LARD
            }
        }
        `when`("땅콩버터·피넛버터이면") {
            then("땅콩만 검출하고 버터는 검출하지 않는다") {
                codes(IngredientLabelReader.read(null, "땅콩버터(땅콩, 설탕), 피넛버터초콜릿")) shouldBe setOf(PEANUT)
            }
        }
        `when`("알레르기 표시와 원재료가 같은 품목을 가리키면") {
            then("한 번만 검출하고 출처는 알레르기 표시를 우선한다") {
                val reading = IngredientLabelReader.read("밀", "밀가루(밀:미국산)")
                reading.detections.filter { it.code == WHEAT }.single().source shouldBe IngredientLabelSource.ALLERGEN_DECLARATION
            }
        }
    }

    given("성분표 OCR 텍스트(KB-560 경로) — 원재료 칸 하나에 통째로 넣는다") {
        `when`("여러 줄에 원재료명·알레르기 문구·교차오염 고지가 섞여 있으면") {
            then("같은 규칙으로 모두 찾는다") {
                val ocr = """
                    원재료명 : 밀가루(밀:미국산), 백설탕, 쇼트닝
                    알레르기 유발물질 : 대두, 우유 함유
                    이 제품은 땅콩, 새우를 사용한 제품과 같은 제조시설에서 제조하고 있습니다.
                """.trimIndent()
                val reading = IngredientLabelReader.read(allergens = null, rawMaterials = ocr)
                codes(reading) shouldBe setOf(WHEAT, SOY, MILK, PEANUT, SHRIMP)
                reading.unmappedTokens shouldContain "백설탕"
            }
        }
    }

    given("판정 — 검출만 있고 '없음'은 없다(R-80)") {
        `when`("검출된 품목과 검출되지 않은 품목을 물으면") {
            then("DETECTED 와 UNCONFIRMED 뿐이다 — 아무것도 못 읽어도 SAFE 가 되지 않는다") {
                val reading = IngredientLabelReader.read("우유", "설탕")
                reading.matchOf(MILK) shouldBe IngredientLabelMatch.DETECTED
                reading.matchOf(PEANUT) shouldBe IngredientLabelMatch.UNCONFIRMED
                IngredientLabelReader.read(null, null).let { empty ->
                    empty.detections shouldBe emptyList()
                    IngredientCode.entries.map { empty.matchOf(it) }.toSet() shouldBe setOf(IngredientLabelMatch.UNCONFIRMED)
                }
                IngredientLabelMatch.entries.map { it.name } shouldNotContain "SAFE"
            }
        }
    }

    given("동의어 사전 리소스") {
        `when`("읽으면") {
            then("모든 줄이 81종 코드로 해석되고, 의무 표시 22종이 전부 들어 있다") {
                IngredientLabelReader.mandatoryAllergenCodes() shouldBe setOf(
                    EGG, MILK, IngredientCode.BUCKWHEAT, PEANUT, SOY, WHEAT, IngredientCode.MACKEREL, IngredientCode.CRAB, SHRIMP,
                    IngredientCode.PORK, IngredientCode.PEACH, IngredientCode.TOMATO, IngredientCode.SULFITES, IngredientCode.WALNUT,
                    IngredientCode.CHICKEN, IngredientCode.BEEF, IngredientCode.SQUID, CLAM, OYSTER, ABALONE, MUSSEL, IngredientCode.PINE_NUT,
                )
            }
        }
    }
})
