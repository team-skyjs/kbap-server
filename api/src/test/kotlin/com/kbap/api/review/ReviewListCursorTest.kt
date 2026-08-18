package com.kbap.api.review

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.review.ReviewSort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ReviewListCursorTest : BehaviorSpec({
    given("리뷰 목록 복합 커서 코덱") {
        `when`("커서가 비어 있으면") {
            then("정렬과 무관하게 null(첫 페이지)이다") {
                ReviewListCursor.parse(null, ReviewSort.LATEST).shouldBeNull()
                ReviewListCursor.parse(" ", ReviewSort.RATING).shouldBeNull()
            }
        }
        `when`("LATEST 정렬에서 숫자 커서를 파싱하면") {
            then("id 만 채워진다") {
                val position = ReviewListCursor.parse("42", ReviewSort.LATEST)!!
                position.metric.shouldBeNull()
                position.id shouldBe 42L
            }
        }
        `when`("지표 정렬에서 base64 복합 커서를 파싱하면") {
            then("디코딩해 지표와 id 가 채워진다") {
                val position = ReviewListCursor.parse("NF8xMjM", ReviewSort.RATING)!!
                position.metric shouldBe 4L
                position.id shouldBe 123L
            }
        }
        `when`("지표 정렬에 인코딩 없는 원문 복합 커서를 주면") {
            then("FOOD-002 로 거절한다") {
                shouldThrow<BusinessException> { ReviewListCursor.parse("4_123", ReviewSort.RATING) }
                    .errorCode shouldBe ErrorCode.INVALID_CURSOR
            }
        }
        `when`("지표 정렬에 숫자 단일 커서를 주면") {
            then("FOOD-002 로 거절한다") {
                shouldThrow<BusinessException> { ReviewListCursor.parse("42", ReviewSort.HELPFUL) }
                    .errorCode shouldBe ErrorCode.INVALID_CURSOR
            }
        }
        `when`("깨진 커서를 주면") {
            then("전부 FOOD-002 로 거절한다") {
                listOf("-1", "abc", "4_123").forEach { raw ->
                    shouldThrow<BusinessException> { ReviewListCursor.parse(raw, ReviewSort.LATEST) }
                        .errorCode shouldBe ErrorCode.INVALID_CURSOR
                }
                listOf("!!!", "LTFfMg", "YV8y", "MV9i", "MV8yXzM", "Xw", "MV8").forEach { raw ->
                    shouldThrow<BusinessException> { ReviewListCursor.parse(raw, ReviewSort.RATING) }
                        .errorCode shouldBe ErrorCode.INVALID_CURSOR
                }
            }
        }
        `when`("커서를 인코딩하면") {
            then("LATEST 는 id 단일 숫자, 지표 정렬은 metric_id 의 base64url(패딩 없음)이다") {
                ReviewListCursor.encode(ReviewSort.LATEST, metric = 7L, id = 42L) shouldBe "42"
                ReviewListCursor.encode(ReviewSort.FOOD_REVIEW_COUNT, metric = 7L, id = 42L) shouldBe "N180Mg"
            }
        }
        `when`("인코딩한 커서를 같은 정렬로 되파싱하면") {
            then("원래 값으로 돌아온다") {
                ReviewSort.entries.forEach { sort ->
                    val encoded = ReviewListCursor.encode(sort, metric = 3L, id = 99L)
                    val parsed = ReviewListCursor.parse(encoded, sort)!!
                    parsed.id shouldBe 99L
                    if (sort != ReviewSort.LATEST) parsed.metric shouldBe 3L
                }
            }
        }
    }
})
