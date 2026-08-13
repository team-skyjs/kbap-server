package com.kbap.common.domain.community.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class CommentTest : BehaviorSpec({
    fun comment(
        postId: Long = 1L,
        memberId: Long = 1L,
        content: String = "정말 맛있죠",
        parentId: Long? = null,
    ) = Comment(
        postId = postId,
        memberId = memberId,
        content = content,
        parentId = parentId,
    )

    given("Comment 생성 — 본문") {
        `when`("본문이 비어 있으면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { comment(content = "") }
            }
        }
        `when`("본문이 공백뿐이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { comment(content = "   ") }
            }
        }
        `when`("본문이 2000자를 넘으면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { comment(content = "가".repeat(2001)) }
            }
        }
        `when`("본문이 경계값 1자와 2000자이면") {
            then("생성된다") {
                comment(content = "굿").content shouldBe "굿"
                comment(content = "가".repeat(2000)).content.length shouldBe 2000
            }
        }
    }

    given("Comment 수정") {
        `when`("본문을 바꾸면") {
            then("반영되고 editedAt 이 채워진다") {
                val target = comment()
                target.editedAt.shouldBeNull()

                target.update(content = "수정했다")

                target.content shouldBe "수정했다"
                target.editedAt.shouldNotBeNull()
            }
        }
        `when`("수정 내용이 제약을 위반하면") {
            then("예외를 던지고 기존 값이 유지된다") {
                val target = comment(content = "원본")

                shouldThrow<IllegalArgumentException> { target.update(content = "가".repeat(2001)) }

                target.content shouldBe "원본"
                target.editedAt.shouldBeNull()
            }
        }
    }

    given("Comment 소유 판정") {
        `when`("작성자 본인이면") {
            then("true 를 반환한다") {
                comment(memberId = 7L).isOwnedBy(7L) shouldBe true
            }
        }
        `when`("다른 회원이면") {
            then("false 를 반환한다") {
                comment(memberId = 7L).isOwnedBy(8L) shouldBe false
            }
        }
    }

    given("Comment 답글 판정") {
        `when`("parentId 가 없으면") {
            then("최상위 댓글이다") {
                comment(parentId = null).isReply shouldBe false
            }
        }
        `when`("parentId 가 있으면") {
            then("대댓글이다") {
                comment(parentId = 10L).isReply shouldBe true
            }
        }
    }
})
