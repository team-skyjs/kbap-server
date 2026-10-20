package com.kbap.common.domain.processedfood

import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.LocalDateTime

class ProcessedFoodSnapshotTest : BehaviorSpec({
    fun snapshot(name: String = "포카칩 오리지널", raw: String? = "감자", manufacturer: String? = "오리온_청주시", seller: String? = null) =
        ProcessedFoodSnapshot("2011022182815", name, "식품", "과자", "유처리제품", manufacturer, seller, "8801117760106", "66g", raw, "대두", "총 내용량", "http://img/1.jpg", "http://img/2.jpg")

    given("매칭용 정규화 이름") {
        `when`("공백·괄호·가운뎃점·'오리지널' 이 섞인 제품명이면") {
            then("모두 뺀 이름이 된다 — 10/20 실측 매칭 규칙") {
                ProcessedFoodSnapshot.normalizeName("포카칩 오리지널") shouldBe "포카칩"
                ProcessedFoodSnapshot.normalizeName("진라면(매운맛)") shouldBe "진라면매운맛"
                ProcessedFoodSnapshot.normalizeName("빼빼로 오리지널") shouldBe "빼빼로"
                ProcessedFoodSnapshot.normalizeName("홈런볼·초코") shouldBe "홈런볼초코"
            }
        }
    }

    given("변경 감지 해시") {
        `when`("같은 원본이면") {
            then("해시가 같고, 원재료 한 글자만 달라도 해시가 바뀐다") {
                snapshot().contentHash() shouldBe snapshot().contentHash()
                snapshot(raw = "감자,").contentHash() shouldNotBe snapshot().contentHash()
                snapshot().contentHash().length shouldBe 64
            }
        }

        `when`("필드 경계만 다른 두 원본이면(앞 필드 끝 글자가 뒤 필드로 옮겨감)") {
            then("해시가 다르다 — 구분자 없이 이어붙이면 같아지는 경우를 막는다") {
                val a = ProcessedFoodSnapshot("1", "가", null, null, null, "나다", null, null, null, null, null, null, null, null)
                val b = ProcessedFoodSnapshot("1", "가나", null, null, null, "다", null, null, null, null, null, null, null, null)
                a.contentHash() shouldNotBe b.contentHash()
            }
        }
    }

    given("제조사 미기재 판정") {
        `when`("제조원·판매원이 '알수없음'·빈값·'알수없음_주소' 뿐이면") {
            then("미기재로 보고, 한쪽이라도 회사명이 있으면 아니다") {
                snapshot(manufacturer = "알수없음", seller = "").isMakerUnknown() shouldBe true
                snapshot(manufacturer = "알수없음 ", seller = null).isMakerUnknown() shouldBe true
                snapshot(manufacturer = "알수없음_서울시", seller = "알수없음").isMakerUnknown() shouldBe true
                snapshot(manufacturer = "알수없음", seller = "㈜농심_서울").isMakerUnknown() shouldBe false
            }
        }
    }

    given("제품 갱신") {
        `when`("같은 원본으로 다시 갱신하면") {
            then("변경 없음(false)이지만 마지막 본 시각은 오른다, 원본이 바뀌면 true 와 새 값·해시") {
                val first = LocalDateTime.of(2026, 10, 20, 4, 0)
                val product = ProcessedFoodProduct.collect(snapshot(), first)
                val next = first.plusDays(1)

                product.refresh(snapshot(), next) shouldBe false
                product.lastSeenAt shouldBe next
                product.refresh(snapshot(raw = "감자,소금"), next.plusDays(1)) shouldBe true
                product.rawMaterials shouldBe "감자,소금"
                product.contentHash shouldBe snapshot(raw = "감자,소금").contentHash()
                product.normalizedName shouldBe "포카칩"
                product.collectedAt shouldBe first
            }
        }

        `when`("제조사 미기재 제품의 보완 조회를 마치면") {
            then("보완값과 조회 시각이 남고, 다시 조회 대상이 되지 않는다(값이 없어도)") {
                val product = ProcessedFoodProduct.collect(snapshot(manufacturer = "알수없음", seller = "알수없음"), LocalDateTime.of(2026, 10, 20, 4, 0))
                product.needsMakerLookup() shouldBe true

                product.supplementMaker(null, LocalDateTime.of(2026, 10, 20, 4, 1))

                product.needsMakerLookup() shouldBe false
                product.makerSupplement shouldBe null
            }
        }
    }
})
