package com.kbap.common.domain.member

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.MemberRankingEvent
import com.kbap.common.domain.member.model.RankingEventType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException

@SpringBootTest
@Import(MySqlContainerConfig::class)
class MemberRankingEventJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var rankingEventRepository: MemberRankingEventJpaRepository

    init {
        given("랭킹 이벤트 원장") {
            `when`("첫 리뷰 작성 이벤트를 기록하면") {
                then("+1/+1 델타로 저장된다") {
                    val saved = rankingEventRepository.save(
                        MemberRankingEvent.reviewCreated(memberId = 1L, reviewId = 10L, firstReviewOfFood = true),
                    )
                    saved.reviewCountDelta shouldBe 1
                    saved.uniqueFoodCountDelta shouldBe 1
                    saved.event shouldBe RankingEventType.REVIEW_CREATED
                }
            }
            `when`("추가 리뷰 작성 이벤트를 기록하면") {
                then("+1/0 델타로 저장된다") {
                    val saved = rankingEventRepository.save(
                        MemberRankingEvent.reviewCreated(memberId = 1L, reviewId = 11L, firstReviewOfFood = false),
                    )
                    saved.reviewCountDelta shouldBe 1
                    saved.uniqueFoodCountDelta shouldBe 0
                }
            }
            `when`("마지막 리뷰 삭제 이벤트를 기록하면") {
                then("-1/-1 델타로 저장된다") {
                    val saved = rankingEventRepository.save(
                        MemberRankingEvent.reviewDeleted(memberId = 1L, reviewId = 12L, lastReviewOfFood = true),
                    )
                    saved.reviewCountDelta shouldBe -1
                    saved.uniqueFoodCountDelta shouldBe -1
                    saved.event shouldBe RankingEventType.REVIEW_DELETED
                }
            }
            `when`("같은 리뷰의 같은 이벤트를 다시 기록하면") {
                then("unique 제약 위반으로 거부된다") {
                    rankingEventRepository.save(
                        MemberRankingEvent.reviewCreated(memberId = 2L, reviewId = 20L, firstReviewOfFood = true),
                    )
                    shouldThrow<DataIntegrityViolationException> {
                        rankingEventRepository.saveAndFlush(
                            MemberRankingEvent.reviewCreated(memberId = 2L, reviewId = 20L, firstReviewOfFood = false),
                        )
                    }
                }
            }
            `when`("이벤트 존재 여부를 조회하면") {
                then("기록된 조합만 true 다") {
                    rankingEventRepository.save(
                        MemberRankingEvent.reviewDeleted(memberId = 3L, reviewId = 30L, lastReviewOfFood = false),
                    )
                    rankingEventRepository.existsByReviewIdAndEvent(30L, RankingEventType.REVIEW_DELETED).shouldBeTrue()
                    rankingEventRepository.existsByReviewIdAndEvent(30L, RankingEventType.REVIEW_CREATED).shouldBeFalse()
                    rankingEventRepository.existsByReviewIdAndEvent(999L, RankingEventType.REVIEW_DELETED).shouldBeFalse()
                }
            }
        }
    }
}
