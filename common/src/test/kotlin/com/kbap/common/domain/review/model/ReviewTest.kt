package com.kbap.common.domain.review.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ReviewTest : BehaviorSpec({
    fun review(
        memberId: Long = 1L,
        rating: Int = 4,
        servingSpeedRating: Int = 0,
        staffKindnessRating: Int = 0,
        content: String? = "맛있어요",
        imageRefs: List<String>? = null,
        authorCountryCode: String? = "KR",
    ) = Review(
        memberId = memberId,
        foodId = 10L,
        rating = rating,
        servingSpeedRating = servingSpeedRating,
        staffKindnessRating = staffKindnessRating,
        content = content,
        imageRefs = imageRefs,
        authorCountryCode = authorCountryCode,
    )

    fun Review.updateWith(
        rating: Int = 4,
        servingSpeedRating: Int = 0,
        staffKindnessRating: Int = 0,
        content: String? = null,
        imageRefs: List<String>? = null,
        place: ReviewPlace? = null,
    ) = update(
        rating = rating,
        servingSpeedRating = servingSpeedRating,
        staffKindnessRating = staffKindnessRating,
        content = content,
        imageRefs = imageRefs,
        place = place,
    )

    given("Review 생성") {
        `when`("rating 이 0 이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { review(rating = 0) }
            }
        }
        `when`("rating 이 6 이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { review(rating = 6) }
            }
        }
        `when`("rating 이 경계값 1 과 5 이면") {
            then("생성된다") {
                review(rating = 1).rating shouldBe 1
                review(rating = 5).rating shouldBe 5
            }
        }
        `when`("세부 평가가 경계값 0 과 5 이면") {
            then("생성된다") {
                review(servingSpeedRating = 0, staffKindnessRating = 5).servingSpeedRating shouldBe 0
                review(servingSpeedRating = 5, staffKindnessRating = 0).servingSpeedRating shouldBe 5
            }
        }
        `when`("세부 평가가 -1 또는 6 이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { review(servingSpeedRating = -1) }
                shouldThrow<IllegalArgumentException> { review(servingSpeedRating = 6) }
                shouldThrow<IllegalArgumentException> { review(staffKindnessRating = -1) }
                shouldThrow<IllegalArgumentException> { review(staffKindnessRating = 6) }
            }
        }
        `when`("content 가 1001자이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { review(content = "가".repeat(1001)) }
            }
        }
        `when`("content 가 정확히 1000자이면") {
            then("생성된다") {
                review(content = "가".repeat(1000)).content?.length shouldBe 1000
            }
        }
        `when`("content 없이 별점만 주면") {
            then("생성된다") {
                review(content = null).content.shouldBeNull()
            }
        }
        `when`("imageRefs 가 4장이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    review(imageRefs = listOf("a", "b", "c", "d"))
                }
            }
        }
        `when`("imageRefs 가 정확히 3장이면") {
            then("생성된다") {
                review(imageRefs = listOf("a", "b", "c")).imageRefs shouldBe listOf("a", "b", "c")
            }
        }
    }

    given("Review 수정") {
        `when`("rating·content·imageRefs 를 바꾸면") {
            then("값이 반영된다") {
                val target = review(imageRefs = listOf("a"))
                target.updateWith(rating = 5)
                target.rating shouldBe 5
                target.content.shouldBeNull()
                target.imageRefs.shouldBeNull()
            }
        }
        `when`("세부 평가를 1~5 로 바꾸면") {
            then("값이 반영된다") {
                val target = review()
                target.updateWith(servingSpeedRating = 1, staffKindnessRating = 5)
                target.servingSpeedRating shouldBe 1
                target.staffKindnessRating shouldBe 5
            }
        }
        `when`("세부 평가를 0 으로 되돌리면") {
            then("0 이 반영된다") {
                val target = review(servingSpeedRating = 4, staffKindnessRating = 2)
                target.updateWith()
                target.servingSpeedRating shouldBe 0
                target.staffKindnessRating shouldBe 0
            }
        }
        `when`("세부 평가를 범위 밖으로 바꾸면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { review().updateWith(servingSpeedRating = 6) }
                shouldThrow<IllegalArgumentException> { review().updateWith(staffKindnessRating = -1) }
            }
        }
        `when`("rating 을 범위 밖으로 바꾸면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { review().updateWith(rating = 0) }
            }
        }
        `when`("content 를 1001자로 바꾸면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    review().updateWith(content = "가".repeat(1001))
                }
            }
        }
        `when`("imageRefs 를 4장으로 바꾸면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    review().updateWith(imageRefs = listOf("a", "b", "c", "d"))
                }
            }
        }
        `when`("수정해도") {
            then("authorCountryCode 는 그대로다") {
                val target = review(authorCountryCode = "VN")
                target.updateWith(rating = 5, content = "updated")
                target.authorCountryCode shouldBe "VN"
            }
        }
    }

    given("Review 소유 판정") {
        `when`("작성자 memberId 로 확인하면") {
            then("true 다") {
                review(memberId = 7L).isOwnedBy(7L) shouldBe true
            }
        }
        `when`("다른 memberId 로 확인하면") {
            then("false 다") {
                review(memberId = 7L).isOwnedBy(8L) shouldBe false
            }
        }
    }
})
