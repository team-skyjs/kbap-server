package com.kbap.common.domain.community.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class PostingTest : BehaviorSpec({
    fun posting(
        memberId: Long = 1L,
        content: String = "오늘 김치찌개 최고였다",
        imageRefs: List<String>? = null,
        foodIds: List<Long>? = null,
    ) = Posting(
        memberId = memberId,
        content = content,
        imageRefs = imageRefs,
        foodIds = foodIds,
    )

    given("Posting 생성 — 본문") {
        `when`("본문이 비어 있으면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { posting(content = "") }
            }
        }
        `when`("본문이 공백뿐이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { posting(content = "   ") }
            }
        }
        `when`("본문이 2000자를 넘으면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { posting(content = "가".repeat(2001)) }
            }
        }
        `when`("본문이 경계값 1자와 2000자이면") {
            then("생성된다") {
                posting(content = "맛").content shouldBe "맛"
                posting(content = "가".repeat(2000)).content.length shouldBe 2000
            }
        }
    }

    given("Posting 생성 — 사진") {
        `when`("사진이 5장이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { posting(imageRefs = (1..5).map { "community/$it.jpg" }) }
            }
        }
        `when`("사진이 4장이면") {
            then("생성되고 순서가 보존된다") {
                val refs = listOf("community/a.jpg", "community/b.jpg", "community/c.jpg", "community/d.jpg")
                posting(imageRefs = refs).imageRefs shouldBe refs
            }
        }
        `when`("사진을 첨부하지 않으면") {
            then("생성된다") {
                posting(imageRefs = null).imageRefs.shouldBeNull()
            }
        }
    }

    given("Posting 생성 — 음식 태그") {
        `when`("태그가 4개이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { posting(foodIds = listOf(1L, 2L, 3L, 4L)) }
            }
        }
        `when`("같은 음식을 중복 태그하면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { posting(foodIds = listOf(1L, 1L)) }
            }
        }
        `when`("태그가 3개이면") {
            then("생성된다") {
                posting(foodIds = listOf(1L, 2L, 3L)).foodIds shouldBe listOf(1L, 2L, 3L)
            }
        }
    }

    given("Posting 수정") {
        `when`("본문·사진·태그를 바꾸면") {
            then("반영되고 editedAt 이 채워진다") {
                val target = posting()
                target.editedAt.shouldBeNull()

                target.update(content = "수정했다", imageRefs = listOf("community/x.jpg"), foodIds = listOf(9L))

                target.content shouldBe "수정했다"
                target.imageRefs shouldBe listOf("community/x.jpg")
                target.foodIds shouldBe listOf(9L)
                target.editedAt.shouldNotBeNull()
            }
        }
        `when`("사진과 태그를 전부 제거하면") {
            then("제거된다") {
                val target = posting(imageRefs = listOf("community/a.jpg"), foodIds = listOf(1L))

                target.update(content = "사진 뺐다", imageRefs = null, foodIds = null)

                target.imageRefs.shouldBeNull()
                target.foodIds.shouldBeNull()
            }
        }
        `when`("수정 내용이 제약을 위반하면") {
            then("예외를 던지고 기존 값이 유지된다") {
                val target = posting(content = "원본")

                shouldThrow<IllegalArgumentException> {
                    target.update(content = "가".repeat(2001), imageRefs = null, foodIds = null)
                }

                target.content shouldBe "원본"
                target.editedAt.shouldBeNull()
            }
        }
    }

    given("Posting 소유 판정") {
        `when`("작성자 본인이면") {
            then("true 를 반환한다") {
                posting(memberId = 7L).isOwnedBy(7L) shouldBe true
            }
        }
        `when`("다른 회원이면") {
            then("false 를 반환한다") {
                posting(memberId = 7L).isOwnedBy(8L) shouldBe false
            }
        }
    }
})
