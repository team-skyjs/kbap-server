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
                codes(IngredientLabelReader.read(null, "합성향료(버터향료)")) shouldBe emptySet()
                codes(IngredientLabelReader.read(null, "버터향 설탕 우유맛 정제소금")) shouldBe emptySet()
                codes(IngredientLabelReader.read(null, "땅콩버터향, 보리새우향")) shouldBe emptySet()
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
        `when`("대구 복합어(농축·포·추출)이면 — Codex P2") {
            then("대구로 검출하고 원산지 대구산은 검출하지 않는다") {
                codes(IngredientLabelReader.read(null, "대구농축액, 대구포, 대구추출물, 사과(대구산)")) shouldBe setOf(IngredientCode.COD)
                codes(IngredientLabelReader.read(null, "사과(대구산)")) shouldBe emptySet()
            }
        }
        `when`("레닛·렌넷이 원재료에 있으면 — Codex P2") {
            then("레닛으로 검출한다") {
                codes(IngredientLabelReader.read(null, "원유, 정제소금, 레닛, 렌넷")) shouldBe setOf(MILK, IngredientCode.RENNET)
            }
        }
        `when`("바닷가재이면 — Codex P2") {
            then("랍스터만 검출하고 가재는 검출하지 않는다") {
                codes(IngredientLabelReader.read(null, "바닷가재농축액")) shouldBe setOf(IngredientCode.LOBSTER)
                codes(IngredientLabelReader.read(null, "민물가재")) shouldBe setOf(IngredientCode.CRAYFISH)
            }
        }
        `when`("버터밀크이면 — Codex P2") {
            then("우유만 검출하고 버터는 검출하지 않는다") {
                codes(IngredientLabelReader.read(null, "버터밀크분말")) shouldBe setOf(MILK)
            }
        }
        `when`("가다랑어 다시 원료이면 — Codex P2") {
            then("다시로 검출하고 쇠고기다시(소고기 조미료)·다시마는 다시로 잡지 않는다") {
                codes(IngredientLabelReader.read(null, "가쓰오부시, 혼다시, 가츠오다시")) shouldContain IngredientCode.DASHI
                codes(IngredientLabelReader.read(null, "다시")) shouldBe setOf(IngredientCode.DASHI)
                codes(IngredientLabelReader.read(null, "쇠고기다시, 다시마")) shouldBe setOf(IngredientCode.BEEF)
            }
        }
        `when`("메밀가루·꿀 복합어·발효주정이면 — Codex P2") {
            then("메밀가루는 밀이 아니고, 아카시아꿀·사양꿀은 꿀, 발효주정은 알코올이다 — 양파꿀리는 꿀이 아니다") {
                codes(IngredientLabelReader.read(null, "메밀가루")) shouldBe setOf(IngredientCode.BUCKWHEAT)
                codes(IngredientLabelReader.read(null, "아카시아꿀, 사양꿀, 잡화꿀")) shouldBe setOf(IngredientCode.HONEY)
                codes(IngredientLabelReader.read(null, "양파꿀리")) shouldBe setOf(IngredientCode.ONION)
                codes(IngredientLabelReader.read(null, "발효주정, 곡물발효주정")) shouldBe setOf(IngredientCode.ALCOHOL)
            }
        }
        `when`("200건 미매핑 토큰 전수 점검에서 나온 실재료 표기이면 — 같은 계열 선제 보강") {
            then("각 품목으로 검출한다") {
                listOf(
                    "1급 A원유" to setOf(MILK),
                    "밀크초코크림" to setOf(MILK),
                    "저지방가공유" to setOf(MILK),
                    "유기농탈지농축유" to setOf(MILK),
                    "사워크림어니언맛씨즈닝" to setOf(MILK, IngredientCode.ONION),
                    "검은깨페이스트" to setOf(IngredientCode.SESAME),
                    "검은콩분말" to setOf(SOY),
                    "약콩" to setOf(SOY),
                    "콩나물엑기스분말" to setOf(SOY),
                    "게엑기스" to setOf(IngredientCode.CRAB),
                    "게맛살" to setOf(IngredientCode.CRAB, IngredientCode.FISH),
                    "냉동연육" to setOf(IngredientCode.FISH),
                    "명란" to setOf(IngredientCode.FISH),
                    "돈골농축액분말" to setOf(IngredientCode.PORK, IngredientCode.MEAT_BROTH),
                    "돈피 에멀젼" to setOf(IngredientCode.PORK),
                    "사골농축액" to setOf(IngredientCode.BEEF, IngredientCode.MEAT_BROTH),
                    "한우농축액" to setOf(IngredientCode.BEEF),
                    "치킨파우더" to setOf(IngredientCode.CHICKEN),
                    "에틸알콜" to setOf(IngredientCode.ALCOHOL),
                    "포테이토어니언수프맛씨즈닝" to setOf(IngredientCode.POTATO, IngredientCode.ONION),
                    "옥배유" to setOf(IngredientCode.CORN),
                    "밀쌀" to setOf(WHEAT),
                    "우리밀짜장분" to setOf(WHEAT),
                ).forEach { (raw, expected) -> withClue(raw) { codes(IngredientLabelReader.read(null, raw)) shouldBe expected } }
            }
        }
        `when`("식물성 밀크·돈사골이면 — Codex P2") {
            then("우유가 아니라 원재료로, 돈사골은 소가 아니라 돼지로 잡는다") {
                codes(IngredientLabelReader.read(null, "오트밀크, 귀리밀크")) shouldBe setOf(IngredientCode.OAT)
                codes(IngredientLabelReader.read(null, "아몬드밀크")) shouldBe setOf(IngredientCode.ALMOND)
                codes(IngredientLabelReader.read(null, "소이밀크")) shouldBe setOf(SOY)
                codes(IngredientLabelReader.read(null, "라이스밀크")) shouldBe emptySet()
                codes(IngredientLabelReader.read(null, "돈사골농축액")) shouldBe setOf(IngredientCode.PORK, IngredientCode.MEAT_BROTH)
            }
        }
        `when`("무알코올 맥주·식물성 밀크 복합어이면 — Codex P2(같은 계열 3회째 → suppress 방식)") {
            then("토큰 안의 다른 용어가 남아도 그 코드는 지운다 — 견과는 그대로 검출") {
                codes(IngredientLabelReader.read(null, "무알코올맥주, 비알콜와인, 논알코올맥주")) shouldBe emptySet()
                codes(IngredientLabelReader.read(null, "캐슈밀크")) shouldBe setOf(IngredientCode.CASHEW)
                codes(IngredientLabelReader.read(null, "헤이즐넛밀크, 피스타치오밀크, 마카다미아밀크")) shouldBe
                    setOf(IngredientCode.HAZELNUT, IngredientCode.PISTACHIO, IngredientCode.MACADAMIA)
                codes(IngredientLabelReader.read(null, "초코밀크")) shouldBe setOf(MILK)
                codes(IngredientLabelReader.read(null, "맥주효모, 생맥주")) shouldBe setOf(IngredientCode.ALCOHOL)
            }
        }
        `when`("suppress 표기와 진짜 재료가 공백으로 한 토큰에 있으면 — Codex P1") {
            then("suppress 는 자기 낱말에만 적용되고 같은 토큰의 다른 낱말은 그대로 검출한다") {
                codes(IngredientLabelReader.read(null, "캐슈밀크 우유")) shouldBe setOf(IngredientCode.CASHEW, MILK)
                codes(IngredientLabelReader.read(null, "무알코올맥주 소주")) shouldBe setOf(IngredientCode.ALCOHOL)
                codes(IngredientLabelReader.read(null, "무알코올맥주 보리")) shouldBe setOf(IngredientCode.BARLEY)
            }
        }
        `when`("연육제·연육효소이면 — Codex P2") {
            then("생선(연육)으로 잡지 않는다") {
                codes(IngredientLabelReader.read(null, "연육제, 연육효소")) shouldBe emptySet()
                codes(IngredientLabelReader.read(null, "냉동연육")) shouldBe setOf(IngredientCode.FISH)
            }
        }
        `when`("알레르기 표시란에 공백 하나 띄운 부정 표현이 있으면 — Codex P2") {
            then("원재료와 같이 그 품목을 제외한다") {
                codes(IngredientLabelReader.read("우유 무첨가, 대두", null)) shouldBe setOf(SOY)
                IngredientLabelReader.read("우유 무첨가, 대두", null).unmappedTokens shouldContain "우유 무첨가"
                IngredientLabelReader.read(null, "설탕 무첨가 땅콩버터").unmappedTokens shouldContain "설탕 무첨가"
            }
        }
        `when`("이름만 닮은 재료이면 — 선제 보강의 오탐 경계") {
            then("검출하지 않는다 — 밀크씨슬·가공유지·강낭콩·리마콩·다시다·밀크향") {
                codes(IngredientLabelReader.read(null, "밀크씨슬추출물, 가공유지, 강낭콩, 리마콩, 다시다, 밀크향")) shouldBe emptySet()
                codes(IngredientLabelReader.read(null, "밀크시슬추출물, 스위트포테이토분말")) shouldBe emptySet()
            }
        }
        `when`("문장형 표기로 의무 표시 품목에 조사가 붙으면 — Codex P2") {
            then("조사를 떼고 찾는다 — 의무 표시 품목에만 적용해 파이(파)·알이 같은 일반어는 잡지 않는다") {
                codes(IngredientLabelReader.read(null, "밀을 함유, 게가 포함됨, 굴과 잣은 사용")) shouldBe setOf(WHEAT, IngredientCode.CRAB, OYSTER, IngredientCode.PINE_NUT)
                codes(IngredientLabelReader.read(null, "파이, 알이")) shouldBe emptySet()
                codes(IngredientLabelReader.read(null, "밀도 1.2, 게도")) shouldBe emptySet()
            }
        }
        `when`("품목 바로 뒤에 무첨가·불포함·미사용·free 가 오면 — Codex P2") {
            then("그 품목만 제외한다 — 같은 토큰의 다른 품목은 그대로 찾는다") {
                codes(IngredientLabelReader.read(null, "우유 무첨가")) shouldBe emptySet()
                codes(IngredientLabelReader.read(null, "우유무첨가, 대두불포함, 계란 미사용")) shouldBe emptySet()
                codes(IngredientLabelReader.read(null, "설탕 무첨가 땅콩버터")) shouldBe setOf(PEANUT)
                codes(IngredientLabelReader.read(null, "MSG free 우유")) shouldBe setOf(MILK)
                codes(IngredientLabelReader.read(null, "우유 freeze, 계란 미사용라인, 대두 무첨가함")) shouldBe setOf(MILK, EGG, SOY)
            }
        }
        `when`("무알콜·비알코올 표기이면 — Codex P2") {
            then("알코올로 검출하지 않는다 — 에틸알콜·발효주정은 그대로") {
                codes(IngredientLabelReader.read(null, "무알콜음료, 무알코올, 비알콜, 비알코올맥주맛, 논알콜")) shouldBe emptySet()
                codes(IngredientLabelReader.read(null, "무주정, 무에탄올, 비에탄올")) shouldBe emptySet()
                codes(IngredientLabelReader.read(null, "에틸알콜, 발효주정")) shouldBe setOf(IngredientCode.ALCOHOL)
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
        `when`("81종 각 코드를 표기명(label)으로 원재료에 넣으면") {
            then("모든 코드가 검출된다 — 사전에 빠진 코드가 없다. 가금류만 예외: 표시 고시의 알 한정어라 가금육·오리고기로만 잡는다") {
                IngredientCode.entries.filter { it != IngredientCode.POULTRY }
                    .filter { IngredientLabelMatch.UNCONFIRMED == IngredientLabelReader.read(null, it.label).matchOf(it) }
                    .map { "${it.name}(${it.label})" } shouldBe emptyList()
                IngredientLabelReader.read(null, "가금육").matchOf(IngredientCode.POULTRY) shouldBe IngredientLabelMatch.DETECTED
            }
        }
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

    given("KB-842 묶음 판정 표의 하위 재료 표기") {
        `when`("각 표기를 원재료로 읽으면") {
            then("그 하위 재료 코드가 검출된다") {
                com.kbap.common.domain.ingredient.AvoidanceGroupMatrix.rows
                    .filterNot { it.member in codes(IngredientLabelReader.read(null, it.labelTerm)) }
                    .map { "${it.member}(${it.labelTerm})" } shouldBe emptyList()
            }
        }
    }

    given("KB-842 젤라틴·고기 육수·양고기 원문") {
        `when`("젤라틴이 유래 표기 없이 나오면") {
            then("젤라틴으로 읽는다") {
                codes(IngredientLabelReader.read(null, "정제수, 젤라틴, 설탕")) shouldBe setOf(IngredientCode.GELATIN)
            }
        }
        `when`("어류·생선·피쉬·식물성 젤라틴처럼 유래가 붙으면(띄어 써도)") {
            then("젤라틴으로 세지 않고, 어류 유래는 생선으로 읽는다") {
                codes(IngredientLabelReader.read(null, "어류젤라틴, 피쉬 젤라틴")) shouldBe setOf(IngredientCode.FISH)
                codes(IngredientLabelReader.read(null, "생선 젤라틴")) shouldBe setOf(IngredientCode.FISH)
                codes(IngredientLabelReader.read(null, "식물성젤라틴, 식물성 젤라틴")) shouldBe emptySet()
                codes(IngredientLabelReader.read("어류 젤라틴", null)) shouldBe setOf(IngredientCode.FISH)
            }
        }
        `when`("뼈 육수·스톡 용어가 나오면") {
            then("원래 고기와 고기 육수를 함께 읽는다") {
                codes(IngredientLabelReader.read(null, "사골농축액")) shouldBe setOf(IngredientCode.BEEF, IngredientCode.MEAT_BROTH)
                codes(IngredientLabelReader.read(null, "우골추출물, 소뼈")) shouldBe setOf(IngredientCode.BEEF, IngredientCode.MEAT_BROTH)
                codes(IngredientLabelReader.read(null, "돈골엑기스")) shouldBe setOf(IngredientCode.PORK, IngredientCode.MEAT_BROTH)
                codes(IngredientLabelReader.read(null, "치킨스톡")) shouldBe setOf(IngredientCode.CHICKEN, IngredientCode.MEAT_BROTH)
                codes(IngredientLabelReader.read(null, "비프스톡")) shouldBe setOf(IngredientCode.BEEF, IngredientCode.MEAT_BROTH)
                codes(IngredientLabelReader.read(null, "닭육수")) shouldBe setOf(IngredientCode.CHICKEN, IngredientCode.MEAT_BROTH, IngredientCode.BROTH)
            }
        }
        `when`("육수·스톡·갈비 구를 띄어 쓰면(표시란·OCR) — Codex P1") {
            then("붙여 쓴 것과 같이 읽는다") {
                codes(IngredientLabelReader.read("고기 육수 함유", null)) shouldBe setOf(IngredientCode.MEAT_BROTH, IngredientCode.BROTH)
                codes(IngredientLabelReader.read("닭 육수", null)) shouldBe setOf(IngredientCode.CHICKEN, IngredientCode.MEAT_BROTH, IngredientCode.BROTH)
                codes(IngredientLabelReader.read(null, "사골 육수")) shouldBe setOf(IngredientCode.BEEF, IngredientCode.MEAT_BROTH, IngredientCode.BROTH)
                codes(IngredientLabelReader.read(null, "치킨 스톡, 비프 스톡")) shouldBe setOf(IngredientCode.CHICKEN, IngredientCode.BEEF, IngredientCode.MEAT_BROTH)
                codes(IngredientLabelReader.read("양 갈비", null)) shouldBe setOf(IngredientCode.LAMB)
            }
        }
        `when`("고기 이름이 붙은 육수면(붙여 쓰든 띄어 쓰든, 표시란이든 원재료든) — 보안 리뷰") {
            then("고기 육수와 함께 원래 고기도 잃지 않는다") {
                listOf("소고기육수", "소고기 육수", "쇠고기 육수").forEach { term ->
                    codes(IngredientLabelReader.read(null, term)) shouldBe setOf(IngredientCode.BEEF, IngredientCode.MEAT_BROTH, IngredientCode.BROTH)
                    codes(IngredientLabelReader.read(term, null)) shouldBe setOf(IngredientCode.BEEF, IngredientCode.MEAT_BROTH, IngredientCode.BROTH)
                }
                codes(IngredientLabelReader.read(null, "돼지고기 육수")) shouldBe setOf(IngredientCode.PORK, IngredientCode.MEAT_BROTH, IngredientCode.BROTH)
                codes(IngredientLabelReader.read("돼지고기 육수", null)) shouldBe setOf(IngredientCode.PORK, IngredientCode.MEAT_BROTH, IngredientCode.BROTH)
            }
        }
        `when`("'고기'로 끝나는 다른 낱말 뒤 육수면 — Codex P2") {
            then("물고기 육수는 생선·육수이지 고기 육수가 아니고, 불고기는 고기 육수가 아니다") {
                listOf("물고기 육수", "물고기육수").forEach { term ->
                    codes(IngredientLabelReader.read(null, term)) shouldBe setOf(IngredientCode.FISH, IngredientCode.BROTH)
                    codes(IngredientLabelReader.read(term, null)) shouldBe setOf(IngredientCode.FISH, IngredientCode.BROTH)
                }
                (IngredientCode.MEAT_BROTH in codes(IngredientLabelReader.read(null, "불고기"))) shouldBe false
            }
        }
        `when`("돼지 육수면(고기 없이) — 공부 참고") {
            then("돼지고기와 고기 육수·육수") {
                listOf("돼지 육수", "돼지육수").forEach { term ->
                    codes(IngredientLabelReader.read(null, term)) shouldBe setOf(IngredientCode.PORK, IngredientCode.MEAT_BROTH, IngredientCode.BROTH)
                }
            }
        }
        `when`("양고기 용어가 나오면") {
            then("양고기로 읽고, 양파·양지는 양고기가 아니다") {
                codes(IngredientLabelReader.read(null, "양고기, 머튼, 양갈비")) shouldBe setOf(IngredientCode.LAMB)
                codes(IngredientLabelReader.read(null, "램")) shouldBe setOf(IngredientCode.LAMB)
                (IngredientCode.LAMB in codes(IngredientLabelReader.read(null, "양파, 양지, 램프"))) shouldBe false
            }
        }
    }
})
