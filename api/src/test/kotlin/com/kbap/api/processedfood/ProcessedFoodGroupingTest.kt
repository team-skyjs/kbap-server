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
})
