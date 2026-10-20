package com.kbap.common.domain.processedfood

import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class ProcessedFoodProductTest : BehaviorSpec({
    given("표시용 제조사 이름") {
        `when`("HACCP 제조원 원문이 회사 형태·공장명·주소와 섞여 있으면(로컬 미러 실측 표기)") {
            then("업체명만 남긴다") {
                listOf(
                    "㈜농심_서울특별시 동작구 여의대방로 112(신대방동)" to "농심",
                    "농심 본사:서울특별시 동작구 여의대방로 112(신대방동)" to "농심",
                    "농심본사:서울특별시 동작구 여의대방로112(신대방동)http://www.nongshim.com" to "농심",
                    "㈜오리온 제4청주공장 충청북도 청주시 흥덕구 월명로 249" to "오리온",
                    "㈜오리온 / 제3익산공장 전라북도 익산시 동서로 317" to "오리온",
                    "롯데제과주식회사F2:경남 양산시 양산대로 1158" to "롯데제과",
                    "롯데제과㈜경남 양산시 양산대로 1158" to "롯데제과",
                    "해태제과식품㈜/F2/대구공장:경상북도 경산시 하양읍 대경로 541" to "해태제과식품",
                    "오뚜기라면주식회사 경기도 평택시 안중읍 덕우로 423" to "오뚜기라면",
                    "주식회사 오뚜기/경기도 안양시 동안구 흥안대로 405" to "오뚜기",
                    "농업회사법인 (주)담양한과명진식품_전라남도 담양군 창평현로 71436" to "담양한과명진식품",
                    "코카-콜라음료㈜ 경남 양산시 충렬로 269(유산동)" to "코카-콜라음료",
                    "삼양식품㈜:서울특별시 성북구 오패산로 3길 104/원주공장" to "삼양식품",
                    "(주)엔피베버리지" to "엔피베버리지",
                    "CJ 제일제당 주식회사_서울특별시 중구 동호로 330" to "CJ 제일제당",
                    "주식회사 팔도/서울특별시 서초구 강남대로 577" to "팔도",
                    "㈜팔도 경기도 이천시" to "팔도",
                    "㈜제이앤이 아산공장 / 충남 아산시 영인면 장영실로 729" to "제이앤이",
                    "오뚜기라면주식회사 경기도 평택시 안중읍 덕우로 423" to "오뚜기라면",
                ).forEach { (raw, expected) ->
                    withClue(raw) { ProcessedFoodProduct(manufacturer = raw).displayMaker() shouldBe expected }
                }
            }
        }
        `when`("업체명이 없이 본사·공장·알수없음뿐이면") {
            then("판매원·보완값 순으로 넘어가고 다 없으면 null") {
                ProcessedFoodProduct(manufacturer = "본사:서울특별시 동작구 여의대방로112(신대방동)").displayMaker() shouldBe null
                ProcessedFoodProduct(manufacturer = "원주공장").displayMaker() shouldBe null
                ProcessedFoodProduct(manufacturer = "알수없음", seller = "㈜농심/서울").displayMaker() shouldBe "농심"
                ProcessedFoodProduct(manufacturer = "알수없음", makerSupplement = "오뚜기").displayMaker() shouldBe "오뚜기"
                ProcessedFoodProduct(manufacturer = "알수없음", makerSupplement = "㈜농심").displayMaker() shouldBe "농심"
                ProcessedFoodProduct(manufacturer = "알수없음", makerSupplement = "삼양식품(주)원주공장").displayMaker() shouldBe "삼양식품"
            }
        }
    }
    given("제품 이미지 URL") {
        `when`("HACCP 이미지가 http 로 오면") {
            then("저장은 원본 그대로(500자 절단·해시와 어긋나지 않게), 응답용 값만 https 로 바꾼다") {
                val snapshot = ProcessedFoodSnapshot(
                    "1", "새우깡", null, "과자", null, "농심", null, null, null, null, null, null,
                    "http://www.haccp.or.kr/fresh/prdimg/1/1-1.jpg", "http://www.haccp.or.kr/fresh/prdimg/1/1-2.jpg",
                )
                val product = ProcessedFoodProduct.collect(snapshot, LocalDateTime.of(2026, 10, 21, 0, 0))
                product.imageUrl shouldBe "http://www.haccp.or.kr/fresh/prdimg/1/1-1.jpg"
                product.contentHash shouldBe snapshot.contentHash()
                ProcessedFoodProduct.secureImageUrl(product.imageUrl) shouldBe "https://www.haccp.or.kr/fresh/prdimg/1/1-1.jpg"
                ProcessedFoodProduct.secureImageUrl("http://haccp.or.kr/a.jpg") shouldBe "https://haccp.or.kr/a.jpg"
            }
        }
        `when`("다른 호스트의 http 이미지이면") {
            then("https 를 보장할 수 없어 응답에서도 그대로 둔다") {
                ProcessedFoodProduct.secureImageUrl("http://img.example.com/a.jpg") shouldBe "http://img.example.com/a.jpg"
                ProcessedFoodProduct.secureImageUrl(null) shouldBe null
            }
        }
    }
})
