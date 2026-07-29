package com.kbap.common.domain.review

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.review.model.Review
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest

@SpringBootTest(classes = [ReviewTestApp::class])
@Import(MySqlContainerConfig::class)
class ReviewJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var reviewJpaRepository: ReviewJpaRepository

    init {
        fun save(
            memberId: Long,
            foodId: Long,
            rating: Int = 4,
            countryCode: String? = "KR",
        ): Review = reviewJpaRepository.save(
            Review(memberId = memberId, foodId = foodId, rating = rating, authorCountryCode = countryCode),
        )

        fun page(size: Int) = PageRequest.of(0, size)

        given("findFoodReviewPage — 음식별 최신순 keyset") {
            val foodId = 100L
            val saved = (1..25).map { save(memberId = it.toLong(), foodId = foodId) }

            `when`("cursor null, size 21 로 조회하면") {
                then("최신(id desc) 21건을 준다") {
                    val result = reviewJpaRepository.findFoodReviewPage(foodId, null, null, page(21))
                    result.size shouldBe 21
                    result.first().id shouldBe saved.last().id
                    result.map { it.id } shouldBe result.map { it.id }.sortedDescending()
                }
            }
            `when`("cursor 를 21번째 리뷰 id 로 주면") {
                then("그보다 작은 id 만 준다") {
                    val cursor = saved[4].id
                    val result = reviewJpaRepository.findFoodReviewPage(foodId, null, cursor, page(21))
                    result.size shouldBe 4
                    result.all { it.id < cursor } shouldBe true
                }
            }
            `when`("다른 음식의 리뷰가 있으면") {
                save(memberId = 999L, foodId = 101L)
                then("대상 음식 리뷰만 준다") {
                    val result = reviewJpaRepository.findFoodReviewPage(101L, null, null, page(21))
                    result.size shouldBe 1
                    result.first().foodId shouldBe 101L
                }
            }
        }

        given("findFoodReviewPage — 국적 필터") {
            val foodId = 200L
            save(memberId = 1L, foodId = foodId, countryCode = "KR")
            save(memberId = 2L, foodId = foodId, countryCode = "KR")
            save(memberId = 3L, foodId = foodId, countryCode = "VN")
            save(memberId = 4L, foodId = foodId, countryCode = null)

            `when`("countryCode 를 KR 로 주면") {
                then("KR 스냅샷 리뷰만 준다") {
                    val result = reviewJpaRepository.findFoodReviewPage(foodId, "KR", null, page(21))
                    result.size shouldBe 2
                    result.all { it.authorCountryCode == "KR" } shouldBe true
                }
            }
            `when`("countryCode 를 null 로 주면") {
                then("국적 미보유 리뷰까지 전체를 준다") {
                    reviewJpaRepository.findFoodReviewPage(foodId, null, null, page(21)).size shouldBe 4
                }
            }
            `when`("리뷰가 없는 국적 코드를 주면") {
                then("빈 목록을 준다") {
                    reviewJpaRepository.findFoodReviewPage(foodId, "JP", null, page(21)).shouldBeEmpty()
                }
            }
        }

        given("findMemberReviewPage — 내 리뷰 keyset") {
            val memberId = 300L
            val mine = (1..3).map { save(memberId = memberId, foodId = 300L + it) }
            save(memberId = 301L, foodId = 300L)

            `when`("cursor null 로 조회하면") {
                then("본인 리뷰만 최신순으로 준다") {
                    val result = reviewJpaRepository.findMemberReviewPage(memberId, null, page(21))
                    result.map { it.id } shouldBe mine.map { it.id }.sortedDescending()
                }
            }
            `when`("cursor 를 두 번째 최신 id 로 주면") {
                then("그보다 오래된 리뷰만 준다") {
                    val cursor = mine[1].id
                    val result = reviewJpaRepository.findMemberReviewPage(memberId, cursor, page(21))
                    result.map { it.id } shouldBe listOf(mine[0].id)
                }
            }
        }

        given("소프트 삭제된 리뷰") {
            val foodId = 400L
            val kept = save(memberId = 1L, foodId = foodId, rating = 5)
            val deleted = save(memberId = 2L, foodId = foodId, rating = 1)
            deleted.delete()
            reviewJpaRepository.save(deleted)

            `when`("목록을 조회하면") {
                then("삭제 리뷰는 제외된다") {
                    val result = reviewJpaRepository.findFoodReviewPage(foodId, null, null, page(21))
                    result.map { it.id } shouldBe listOf(kept.id)
                }
            }
            `when`("평점을 집계하면") {
                then("삭제 리뷰는 평균·건수에서 빠진다") {
                    val aggregate = reviewJpaRepository.aggregateRating(foodId, null)
                    aggregate.reviewCount shouldBe 1L
                    aggregate.average.shouldNotBeNull() shouldBe (5.0 plusOrMinus 0.0001)
                }
            }
        }

        given("aggregateRating — 평점 집계") {
            val foodId = 500L
            save(memberId = 1L, foodId = foodId, rating = 4, countryCode = "KR")
            save(memberId = 2L, foodId = foodId, rating = 5, countryCode = "KR")
            save(memberId = 3L, foodId = foodId, rating = 2, countryCode = "VN")

            `when`("countryCode null 로 집계하면") {
                then("전체 평균과 건수를 준다") {
                    val aggregate = reviewJpaRepository.aggregateRating(foodId, null)
                    aggregate.reviewCount shouldBe 3L
                    aggregate.average.shouldNotBeNull() shouldBe (11.0 / 3 plusOrMinus 0.0001)
                }
            }
            `when`("countryCode 를 KR 로 집계하면") {
                then("KR 스냅샷 리뷰만 집계한다") {
                    val aggregate = reviewJpaRepository.aggregateRating(foodId, "KR")
                    aggregate.reviewCount shouldBe 2L
                    aggregate.average.shouldNotBeNull() shouldBe (4.5 plusOrMinus 0.0001)
                }
            }
            `when`("리뷰가 없는 음식을 집계하면") {
                then("평균 null·건수 0 을 준다") {
                    val aggregate = reviewJpaRepository.aggregateRating(501L, null)
                    aggregate.reviewCount shouldBe 0L
                    aggregate.average.shouldBeNull()
                }
            }
        }

        given("countByMemberIdAndFoodId — 첫/마지막 리뷰 판정") {
            val memberId = 600L
            val foodId = 600L
            save(memberId = memberId, foodId = foodId)
            val second = save(memberId = memberId, foodId = foodId)

            `when`("같은 음식에 2건 작성한 상태면") {
                then("2 를 준다") {
                    reviewJpaRepository.countByMemberIdAndFoodId(memberId, foodId) shouldBe 2L
                }
            }
            `when`("그중 1건을 소프트 삭제하면") {
                second.delete()
                reviewJpaRepository.save(second)
                then("1 을 준다") {
                    reviewJpaRepository.countByMemberIdAndFoodId(memberId, foodId) shouldBe 1L
                }
            }
        }
    }
}
