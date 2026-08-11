package com.kbap.common.domain.review

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
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
import javax.sql.DataSource

@SpringBootTest(classes = [ReviewTestApp::class])
@Import(MySqlContainerConfig::class)
class ReviewJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var reviewJpaRepository: ReviewJpaRepository

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun seedMember(memberId: Long): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO member (id, provider, provider_uid, avoidance_substance_codes,
                                        onboarding_completed, scan_count, review_count, unique_reviewed_food_count,
                                        status, created_at, updated_at)
                    VALUES (?, 'GOOGLE', ?, '[]', 1, 0, 0, 0, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE id = id
                    """,
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.setString(2, "review-repo-test-$memberId")
                    ps.executeUpdate()
                }
            }

        fun withdrawMember(memberId: Long): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement("UPDATE member SET status = 'DELETED' WHERE id = ?").use { ps ->
                    ps.setLong(1, memberId)
                    ps.executeUpdate()
                }
            }

        fun save(
            memberId: Long,
            foodId: Long,
            rating: Int = 4,
            countryCode: String? = "KR",
        ): Review {
            seedMember(memberId)
            return reviewJpaRepository.save(
                Review(memberId = memberId, foodId = foodId, rating = rating, authorCountryCode = countryCode),
            )
        }

        fun page(size: Int) = PageRequest.of(0, size)

        given("findFoodReviewPage — 음식별 최신순 keyset") {
            val foodId = 100L
            val saved = (1..25).map { save(memberId = it.toLong(), foodId = foodId) }

            `when`("cursor null, size 21 로 조회하면") {
                then("최신(id desc) 21건을 준다") {
                    val result = reviewJpaRepository.findFoodReviewPage(foodId, null, null, listOf(-1L), listOf(-1L), page(21))
                    result.size shouldBe 21
                    result.first().id shouldBe saved.last().id
                    result.map { it.id } shouldBe result.map { it.id }.sortedDescending()
                }
            }
            `when`("cursor 를 21번째 리뷰 id 로 주면") {
                then("그보다 작은 id 만 준다") {
                    val cursor = saved[4].id
                    val result = reviewJpaRepository.findFoodReviewPage(foodId, null, cursor, listOf(-1L), listOf(-1L), page(21))
                    result.size shouldBe 4
                    result.all { it.id < cursor } shouldBe true
                }
            }
            `when`("다른 음식의 리뷰가 있으면") {
                save(memberId = 999L, foodId = 101L)
                then("대상 음식 리뷰만 준다") {
                    val result = reviewJpaRepository.findFoodReviewPage(101L, null, null, listOf(-1L), listOf(-1L), page(21))
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
                    val result = reviewJpaRepository.findFoodReviewPage(foodId, "KR", null, listOf(-1L), listOf(-1L), page(21))
                    result.size shouldBe 2
                    result.all { it.authorCountryCode == "KR" } shouldBe true
                }
            }
            `when`("countryCode 를 null 로 주면") {
                then("국적 미보유 리뷰까지 전체를 준다") {
                    reviewJpaRepository.findFoodReviewPage(foodId, null, null, listOf(-1L), listOf(-1L), page(21)).size shouldBe 4
                }
            }
            `when`("리뷰가 없는 국적 코드를 주면") {
                then("빈 목록을 준다") {
                    reviewJpaRepository.findFoodReviewPage(foodId, "JP", null, listOf(-1L), listOf(-1L), page(21)).shouldBeEmpty()
                }
            }
        }

        given("findFoodReviewPage — 제외 id 목록") {
            val foodId = 700L
            val saved = (1..5).map { save(memberId = it.toLong(), foodId = foodId) }
            val excluded = listOf(saved[1].id, saved[3].id)

            `when`("제외 id 목록을 주면") {
                then("제외 id 를 뺀 최신순 목록을 준다") {
                    val result = reviewJpaRepository.findFoodReviewPage(foodId, null, null, listOf(-1L), excluded, page(21))
                    result.map { it.id } shouldBe listOf(saved[4].id, saved[2].id, saved[0].id)
                }
            }
            `when`("커서와 함께 제외 목록을 주면") {
                then("커서 미만에서 제외 id 만 뺀다") {
                    val result = reviewJpaRepository.findFoodReviewPage(foodId, null, saved[4].id, listOf(-1L), excluded, page(21))
                    result.map { it.id } shouldBe listOf(saved[2].id, saved[0].id)
                }
            }
            `when`("국적 필터와 함께 제외 목록을 주면") {
                then("두 조건을 모두 적용한다") {
                    val result = reviewJpaRepository.findFoodReviewPage(foodId, "KR", null, listOf(-1L), excluded, page(21))
                    result.map { it.id } shouldBe listOf(saved[4].id, saved[2].id, saved[0].id)
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

        given("findFoodReviewPage — 차단 회원 제외 필터") {
            val foodId = 750L
            val kept = save(memberId = 701L, foodId = foodId)
            save(memberId = 702L, foodId = foodId)

            `when`("excludedMemberIds 에 작성자를 넣으면") {
                then("그 작성자의 리뷰만 빠진다") {
                    val result = reviewJpaRepository.findFoodReviewPage(foodId, null, null, listOf(702L), listOf(-1L), page(21))
                    result.map { it.id } shouldBe listOf(kept.id)
                }
            }
            `when`("센티널 -1 만 넣으면") {
                then("아무도 제외되지 않는다") {
                    reviewJpaRepository.findFoodReviewPage(foodId, null, null, listOf(-1L), listOf(-1L), page(21)).size shouldBe 2
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
                    val result = reviewJpaRepository.findFoodReviewPage(foodId, null, null, listOf(-1L), listOf(-1L), page(21))
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

        given("findGlobalReviewPage — 전체 피드 keyset") {
            fun saveFood(koreanName: String): Food = foodJpaRepository.save(Food(koreanName = koreanName))

            val foodA = saveFood("전체피드음식A")
            val foodB = saveFood("전체피드음식B")
            val saved = (1..25).map { save(memberId = it.toLong(), foodId = if (it % 2 == 0) foodA.id else foodB.id) }

            `when`("cursor null, size 21 로 조회하면") {
                then("음식 구분 없이 최신(id desc) 21건을 준다") {
                    val result = reviewJpaRepository.findGlobalReviewPage(null, listOf(-1L), listOf(-1L), page(21))
                    result.size shouldBe 21
                    result.first().id shouldBe saved.last().id
                    result.map { it.id } shouldBe result.map { it.id }.sortedDescending()
                    result.map { it.foodId }.toSet() shouldBe setOf(foodA.id, foodB.id)
                }
            }
            `when`("cursor 를 다섯 번째 리뷰 id 로 주면") {
                then("그보다 작은 id 만 준다") {
                    val cursor = saved[4].id
                    val result = reviewJpaRepository.findGlobalReviewPage(cursor, listOf(-1L), listOf(-1L), page(21))
                    result.all { it.id < cursor } shouldBe true
                    result.map { it.id } shouldBe saved.take(4).map { it.id }.sortedDescending()
                }
            }
            `when`("가장 오래된 리뷰 id 를 cursor 로 주면") {
                then("빈 목록을 준다") {
                    reviewJpaRepository.findGlobalReviewPage(saved.first().id, listOf(-1L), listOf(-1L), page(21))
                        .shouldBeEmpty()
                }
            }
        }

        given("findGlobalReviewPage — 제외 규칙") {
            fun saveFood(koreanName: String): Food = foodJpaRepository.save(Food(koreanName = koreanName))

            `when`("excludedMemberIds 에 작성자를 넣으면") {
                then("그 작성자의 리뷰가 빠진다") {
                    val food = saveFood("전체피드차단음식")
                    val kept = save(memberId = 901L, foodId = food.id)
                    save(memberId = 902L, foodId = food.id)

                    val result = reviewJpaRepository.findGlobalReviewPage(null, listOf(902L), listOf(-1L), page(21))
                    result.map { it.id }.contains(kept.id) shouldBe true
                    result.all { it.memberId != 902L } shouldBe true
                }
            }
            `when`("excludedReviewIds 에 리뷰를 넣으면") {
                then("그 리뷰가 빠진다") {
                    val food = saveFood("전체피드신고음식")
                    val kept = save(memberId = 903L, foodId = food.id)
                    val reported = save(memberId = 903L, foodId = food.id)

                    val result = reviewJpaRepository.findGlobalReviewPage(null, listOf(-1L), listOf(reported.id), page(21))
                    result.map { it.id }.contains(kept.id) shouldBe true
                    result.map { it.id }.contains(reported.id) shouldBe false
                }
            }
            `when`("음식이 소프트 삭제되면") {
                then("그 음식의 리뷰가 피드에서 빠진다") {
                    val alive = saveFood("전체피드생존음식")
                    val deleted = saveFood("전체피드삭제음식")
                    val kept = save(memberId = 904L, foodId = alive.id)
                    val orphaned = save(memberId = 904L, foodId = deleted.id)
                    deleted.delete()
                    foodJpaRepository.save(deleted)

                    val result = reviewJpaRepository.findGlobalReviewPage(null, listOf(-1L), listOf(-1L), page(100))
                    result.map { it.id }.contains(kept.id) shouldBe true
                    result.map { it.id }.contains(orphaned.id) shouldBe false
                }
            }
            `when`("food 행이 없는 foodId 의 리뷰가 있으면") {
                then("피드에서 빠진다") {
                    val ghost = save(memberId = 905L, foodId = 999_999L)
                    val result = reviewJpaRepository.findGlobalReviewPage(null, listOf(-1L), listOf(-1L), page(100))
                    result.map { it.id }.contains(ghost.id) shouldBe false
                }
            }
            `when`("작성자가 탈퇴하면") {
                then("그 작성자의 리뷰가 피드에서 빠진다") {
                    val food = saveFood("전체피드탈퇴음식")
                    val kept = save(memberId = 906L, foodId = food.id)
                    val withdrawn = save(memberId = 907L, foodId = food.id)
                    withdrawMember(907L)

                    val result = reviewJpaRepository.findGlobalReviewPage(null, listOf(-1L), listOf(-1L), page(100))
                    result.map { it.id }.contains(kept.id) shouldBe true
                    result.map { it.id }.contains(withdrawn.id) shouldBe false
                }
            }
        }

        given("findFoodReviewPage — 탈퇴 회원 제외") {
            `when`("작성자가 탈퇴하면") {
                then("그 작성자의 리뷰가 목록에서 빠진다") {
                    val foodId = 760L
                    val kept = save(memberId = 761L, foodId = foodId)
                    val withdrawn = save(memberId = 762L, foodId = foodId)
                    withdrawMember(762L)

                    val result = reviewJpaRepository.findFoodReviewPage(foodId, null, null, listOf(-1L), listOf(-1L), page(21))
                    result.map { it.id } shouldBe listOf(kept.id)
                    result.map { it.id }.contains(withdrawn.id) shouldBe false
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
