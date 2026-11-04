package com.kbap.api.processedfood

import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ProcessedFoodGroupingTest : BehaviorSpec({
    fun key(name: String, maker: String = "농심") = ProcessedFoodGrouping.keyOf(ProcessedFoodSnapshot.normalizeName(name), maker)

    given("용량·용기만 다른 가공식품 묶음(예진 11/2)") {
        `when`("이름이 용기·용량 낱말만 다르면") {
            then("같은 제조사 안에서 한 묶음이다") {
                key("앵그리짜파구리") shouldBe key("앵그리짜파구리큰사발면")
                key("육개장사발면") shouldBe key("육개장 큰컵")
                listOf("신라면컵", "신라면 큰사발", "신라면 120g", "신라면 멀티팩", "신라면(5입)", "신라면 CUP", "신라면 미니컵", "신라면 봉지").forEach { key(it) shouldBe key("신라면") }
                key("신라면 큰사발면 115g") shouldBe key("신라면")
            }
        }
        `when`("제조사가 다르면") {
            then("이름이 같아져도 묶지 않는다") {
                key("짜장컵", "오뚜기") shouldNotBe key("짜장", "농심")
            }
        }
        `when`("크기 낱말이 상품을 바꾸거나 브랜드의 일부면") {
            then("묶지 않는다 — 미니·빅 같은 크기 형용사는 별개 상품(미니약과), 왕뚜껑·컵누들·컵반은 브랜드") {
                key("미니약과", "삼립") shouldNotBe key("약과", "삼립")
                key("빅파이", "크라운") shouldNotBe key("파이", "크라운")
                key("왕뚜껑", "팔도") shouldNotBe key("뚜껑", "팔도")
                key("컵누들", "오뚜기") shouldNotBe key("누들", "오뚜기")
                key("컵반", "CJ") shouldNotBe key("반", "CJ")
            }
        }
        `when`("크기 낱말을 빼면 빈 이름이나 일반명사만 남으면") {
            then("묶지 않는다") {
                key("라면컵") shouldNotBe key("라면")
                key("우동 큰사발") shouldNotBe key("우동")
                key("컵") shouldNotBe key("팩")
            }
        }
    }
    given("브랜드 접두·업소용만 다른 가공식품 묶음(S-954, 운영 진라면 8건)") {
        val ottogi = "오뚜기라면"
        `when`("이름 앞부분이 제조사 이름 또는 회사 접미(라면·식품·제과 등)를 뗀 제조사 이름과 같으면") {
            then("그 접두를 떼고 같은 맛끼리 묶는다 — 제조사 오뚜기라면의 '오뚜기진라면' = '진라면'") {
                key("오뚜기 진라면 순한맛", ottogi) shouldBe key("진라면(순한맛)", ottogi)
                key("오뚜기진라면매운맛", ottogi) shouldBe key("진라면(매운맛)", ottogi)
                key("농심신라면", "농심") shouldBe key("신라면", "농심")
            }
        }
        `when`("이름 끝이 업소용이면") {
            then("용량 낱말처럼 떼고 묶는다") {
                key("진라면 매운맛 업소용", ottogi) shouldBe key("진라면(매운맛)", ottogi)
            }
        }
        `when`("Cup·Plus 처럼 이름 가운데 낱말이 다르면") {
            then("다른 상품으로 남긴다 — 컵라면(65g)·Plus 는 포장 단위가 아니라 다른 제품") {
                key("진라면Cup매운맛", ottogi) shouldNotBe key("진라면(매운맛)", ottogi)
                key("진라면Plus순한맛", ottogi) shouldNotBe key("진라면(순한맛)", ottogi)
                key("진라면(매운맛)", ottogi) shouldNotBe key("진라면(순한맛)", ottogi)
            }
        }
        `when`("제조사 키가 등록된 브랜드(해태·롯데·남양 등)로 시작하면") {
            then("그 브랜드 접두를 뗀다 — dev 전량에서 관측된 해태가루비·롯데칠성음료·공장명 붙은 남양유업") {
                key("해태허니버터칩", "해태가루비") shouldBe key("허니버터칩", "해태가루비")
                key("롯데제주사랑감귤사랑", "롯데칠성음료") shouldBe key("제주사랑감귤사랑", "롯데칠성음료")
                key("남양초코에몽", "남양유업천안신공장충남") shouldBe key("초코에몽", "남양유업천안신공장충남")
            }
        }
        `when`("앞부분이 제조사 이름과 우연히 일부만 겹치거나 이름 전체면") {
            then("떼지 않는다 — 오리온의 '오리…', 오뚜기의 '오징어…'") {
                key("오리훈제", "오리온") shouldNotBe key("훈제", "오리온")
                key("오징어짬뽕", "오뚜기") shouldNotBe key("징어짬뽕", "오뚜기")
                key("오뚜기", "오뚜기") shouldNotBe key("", "오뚜기")
            }
        }
        `when`("제조사 표기가 공백·회사 형태만 다르면") {
            then("제조사 키가 같아 한 묶음이다 — (주)팔도 / (주) 팔도") {
                fun maker(raw: String) = com.kbap.common.domain.processedfood.model.ProcessedFoodProduct.displayMakerOf(raw, null, null)
                key("팔도비빔면매운맛", maker("(주)팔도")!!) shouldBe key("팔도비빔면매운맛", maker("(주) 팔도")!!)
            }
        }
    }
    given("포장 형태·용량만 다른 가공식품(예진 11/4 — 용량이 달라도 하나로)") {
        `when`("앞이나 뒤에 큰컵·컵·큰사발·사발이 붙으면") {
            then("떼고 같은 상품으로 묶는다") {
                key("불닭볶음면", "삼양식품") shouldBe key("큰컵 불닭볶음면", "삼양식품")
                key("불닭볶음면", "삼양식품") shouldBe key("컵불닭볶음면", "삼양식품")
                key("치즈불닭볶음면 5입", "삼양식품") shouldBe key("큰컵 치즈불닭볶음면", "삼양식품")
                key("육개장", "농심") shouldBe key("사발 육개장", "농심")
            }
        }
        `when`("컵으로 시작하는 브랜드거나 이름 가운데 컵이면") {
            then("떼지 않는다 — 컵누들·컵반·컵밥, 진라면Cup매운맛") {
                key("컵누들 매콤한맛", "오뚜기") shouldNotBe key("누들 매콤한맛", "오뚜기")
                key("컵반 미역국밥", "CJ") shouldNotBe key("반 미역국밥", "CJ")
                key("진라면Cup매운맛", "오뚜기라면") shouldNotBe key("진라면매운맛", "오뚜기라면")
            }
        }
    }
    given("제조사 모름 행을 브랜드 접두를 떼고 접을 때 — Codex P2(#557 2차)") {
        fun row(name: String, maker: String?) = ProcessedFoodGrouping.GroupRow(name, maker, ProcessedFoodGrouping.keyOf(name, maker))
        `when`("뗀 브랜드가 대상 묶음 제조사와 다르면") {
            then("접지 않는다 — 롯데런천미트(모름)는 동원F&B 런천미트에 붙지 않는다") {
                val keys = ProcessedFoodGrouping.foldUnknownMakers(listOf(row("런천미트", "동원F&B"), row("롯데런천미트", null)))
                keys[1] shouldNotBe keys[0]
            }
        }
        `when`("뗀 브랜드가 대상 묶음 제조사와 맞으면") {
            then("접는다 — 롯데런천미트(모름) → 롯데푸드 런천미트") {
                val keys = ProcessedFoodGrouping.foldUnknownMakers(listOf(row("런천미트", "롯데푸드"), row("롯데런천미트", null)))
                keys[1] shouldBe keys[0]
            }
        }
    }
})
