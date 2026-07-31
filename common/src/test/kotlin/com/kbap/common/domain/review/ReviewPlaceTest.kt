package com.kbap.common.domain.review

import com.kbap.common.domain.review.model.ReviewPlace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class ReviewPlaceTest : BehaviorSpec({
    given("리뷰 식당 정보 생성") {
        `when`("식당명·주소·카카오 장소 id·좌표를 모두 주면") {
            then("그대로 보관한다") {
                val place = ReviewPlace(
                    name = "한밥집 강남점",
                    address = "서울 강남구 테헤란로 123",
                    kakaoPlaceId = "27290047",
                    latitude = BigDecimal("37.4979502"),
                    longitude = BigDecimal("127.0276368"),
                )

                place.name shouldBe "한밥집 강남점"
                place.address shouldBe "서울 강남구 테헤란로 123"
                place.kakaoPlaceId shouldBe "27290047"
                place.latitude shouldBe BigDecimal("37.4979502")
                place.longitude shouldBe BigDecimal("127.0276368")
            }
        }

        `when`("전 항목이 비어 있으면") {
            then("생성에 성공한다") {
                val place = ReviewPlace()

                place.name shouldBe null
                place.isEmpty() shouldBe true
            }
        }

        `when`("일부 항목만 있으면") {
            then("결측 항목은 null 로 남고 비어 있지 않다고 본다") {
                val place = ReviewPlace(name = "한밥집 강남점")

                place.address shouldBe null
                place.isEmpty() shouldBe false
            }
        }

        `when`("식당명이 100자를 넘으면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    ReviewPlace(name = "가".repeat(ReviewPlace.MAX_NAME_LENGTH + 1))
                }
            }
        }

        `when`("주소가 200자를 넘으면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    ReviewPlace(address = "가".repeat(ReviewPlace.MAX_ADDRESS_LENGTH + 1))
                }
            }
        }

        `when`("카카오 장소 id 가 30자를 넘으면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    ReviewPlace(kakaoPlaceId = "1".repeat(ReviewPlace.MAX_KAKAO_PLACE_ID_LENGTH + 1))
                }
            }
        }

        `when`("위도가 -90~90 을 벗어나면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    ReviewPlace(latitude = BigDecimal("90.0000001"))
                }
            }
        }

        `when`("경도가 -180~180 을 벗어나면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    ReviewPlace(longitude = BigDecimal("-180.0000001"))
                }
            }
        }
    }
})
