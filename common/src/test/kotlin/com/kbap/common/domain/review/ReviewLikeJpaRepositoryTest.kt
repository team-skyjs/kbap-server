package com.kbap.common.domain.review

import com.kbap.common.core.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest(classes = [ReviewTestApp::class])
@Import(MySqlContainerConfig::class)
class ReviewLikeJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var reviewLikeRepository: ReviewLikeJpaRepository

    init {
        given("upsertActive — 좋아요 등록") {
            `when`("처음 등록하면") {
                reviewLikeRepository.upsertActive(reviewId = 100L, memberId = 1L)
                then("ACTIVE 행이 생긴다") {
                    val found = reviewLikeRepository.findByReviewIdAndMemberId(100L, 1L)
                    found.shouldNotBeNull()
                    found.isActive().shouldBeTrue()
                }
            }
            `when`("같은 쌍으로 다시 등록하면") {
                reviewLikeRepository.upsertActive(reviewId = 101L, memberId = 1L)
                reviewLikeRepository.upsertActive(reviewId = 101L, memberId = 1L)
                then("행은 여전히 1개다") {
                    reviewLikeRepository.countByReviewIds(listOf(101L))
                        .single().let {
                            it.reviewId shouldBe 101L
                            it.likeCount shouldBe 1L
                        }
                }
            }
        }

        given("취소와 재등록 — 부활") {
            val reviewId = 200L
            val memberId = 2L
            reviewLikeRepository.upsertActive(reviewId, memberId)
            val originalId = reviewLikeRepository.findByReviewIdAndMemberId(reviewId, memberId).shouldNotBeNull().id

            `when`("소프트 삭제하면") {
                val row = reviewLikeRepository.findByReviewIdAndMemberId(reviewId, memberId).shouldNotBeNull()
                row.delete()
                reviewLikeRepository.save(row)
                then("활성 조회에 잡히지 않는다") {
                    reviewLikeRepository.findByReviewIdAndMemberId(reviewId, memberId).shouldBeNull()
                }
            }
            `when`("취소 후 다시 등록하면") {
                reviewLikeRepository.upsertActive(reviewId, memberId)
                then("같은 행이 ACTIVE 로 부활한다") {
                    val revived = reviewLikeRepository.findByReviewIdAndMemberId(reviewId, memberId)
                    revived.shouldNotBeNull()
                    revived.id shouldBe originalId
                }
            }
        }

        given("countByReviewIds — 리뷰별 좋아요 수 배치 집계") {
            reviewLikeRepository.upsertActive(reviewId = 300L, memberId = 1L)
            reviewLikeRepository.upsertActive(reviewId = 300L, memberId = 2L)
            reviewLikeRepository.upsertActive(reviewId = 300L, memberId = 3L)
            reviewLikeRepository.upsertActive(reviewId = 301L, memberId = 1L)
            reviewLikeRepository.upsertActive(reviewId = 302L, memberId = 9L)
            reviewLikeRepository.findByReviewIdAndMemberId(302L, 9L).shouldNotBeNull().let {
                it.delete()
                reviewLikeRepository.save(it)
            }

            `when`("여러 리뷰 id 로 집계하면") {
                then("리뷰별 활성 좋아요 수를 주고, 취소분·좋아요 없는 리뷰는 결과에 없다") {
                    val counts = reviewLikeRepository.countByReviewIds(listOf(300L, 301L, 302L, 303L))
                        .associate { it.reviewId to it.likeCount }
                    counts shouldBe mapOf(300L to 3L, 301L to 1L)
                }
            }
        }

        given("findLikedReviewIds — 조회 회원의 좋아요 여부 배치 로드") {
            val memberId = 40L
            reviewLikeRepository.upsertActive(reviewId = 400L, memberId = memberId)
            reviewLikeRepository.upsertActive(reviewId = 401L, memberId = memberId)
            reviewLikeRepository.upsertActive(reviewId = 402L, memberId = 41L)
            reviewLikeRepository.upsertActive(reviewId = 403L, memberId = memberId)
            reviewLikeRepository.findByReviewIdAndMemberId(403L, memberId).shouldNotBeNull().let {
                it.delete()
                reviewLikeRepository.save(it)
            }

            `when`("페이지 리뷰 id 목록으로 조회하면") {
                then("그 회원이 좋아요한 활성 리뷰 id 만 준다") {
                    reviewLikeRepository.findLikedReviewIds(memberId, listOf(400L, 401L, 402L, 403L))
                        .shouldContainExactlyInAnyOrder(400L, 401L)
                }
            }
            `when`("좋아요가 하나도 없는 회원으로 조회하면") {
                then("빈 목록을 준다") {
                    reviewLikeRepository.findLikedReviewIds(999L, listOf(400L, 401L)).shouldBeEmpty()
                }
            }
        }
    }
}
