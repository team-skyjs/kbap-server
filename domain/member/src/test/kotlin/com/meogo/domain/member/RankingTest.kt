package com.meogo.domain.member

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class RankingTest : BehaviorSpec({

    given("랭킹 점수 산출") {
        `when`("리뷰 8건·고유 음식 6종·스캔 9회이면") {
            then("점수는 128점이고 등급은 explorer 이며 다음 등급까지 52점 남는다") {
                val ranking = Ranking.of(scanCount = 9, reviewCount = 8, uniqueReviewedFoodCount = 6)

                ranking.score shouldBe 128
                ranking.tier shouldBe RankingTier.EXPLORER
                ranking.tier.level shouldBe 3
                ranking.nextTier shouldBe RankingTier.REGULAR
                ranking.pointsToNext shouldBe 52
            }
        }

        `when`("활동이 전혀 없으면") {
            then("점수는 0점이고 최하 등급이며 다음 등급까지 30점 남는다") {
                val ranking = Ranking.initial()

                ranking.score shouldBe 0
                ranking.tier shouldBe RankingTier.NEWCOMER
                ranking.nextTier shouldBe RankingTier.TASTER
                ranking.pointsToNext shouldBe 30
            }
        }

        `when`("스캔만 했으면") {
            then("스캔 1회당 2점만 쌓인다") {
                Ranking.of(scanCount = 7, reviewCount = 0, uniqueReviewedFoodCount = 0).score shouldBe 14
            }
        }

        `when`("카운트가 음수이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    Ranking.of(scanCount = 0, reviewCount = -1, uniqueReviewedFoodCount = 0)
                }
            }
        }
    }

    given("점수 내역") {
        `when`("리뷰 8건·고유 음식 6종·스캔 9회이면") {
            then("항목별 점수는 80·30·18 이고 합이 총점과 같다") {
                val ranking = Ranking.of(scanCount = 9, reviewCount = 8, uniqueReviewedFoodCount = 6)

                ranking.reviewPoints shouldBe 80
                ranking.diversityPoints shouldBe 30
                ranking.scanPoints shouldBe 18
                (ranking.reviewPoints + ranking.diversityPoints + ranking.scanPoints) shouldBe ranking.score
            }
        }
    }

    given("등급 경계값") {
        `when`("점수가 각 등급의 진입 점수와 정확히 같으면") {
            then("해당 상위 등급으로 판정된다") {
                RankingTier.of(0) shouldBe RankingTier.NEWCOMER
                RankingTier.of(30) shouldBe RankingTier.TASTER
                RankingTier.of(80) shouldBe RankingTier.EXPLORER
                RankingTier.of(180) shouldBe RankingTier.REGULAR
                RankingTier.of(350) shouldBe RankingTier.GOURMET
                RankingTier.of(600) shouldBe RankingTier.KFOOD_MASTER
                RankingTier.of(1000) shouldBe RankingTier.KOREAN_AT_HEART
            }
        }

        `when`("점수가 진입 점수 직전이면") {
            then("아래 등급에 머문다") {
                RankingTier.of(29) shouldBe RankingTier.NEWCOMER
                RankingTier.of(79) shouldBe RankingTier.TASTER
                RankingTier.of(999) shouldBe RankingTier.KFOOD_MASTER
            }
        }
    }

    given("최고 등급 도달") {
        `when`("점수가 1000점 이상이면") {
            then("다음 등급과 남은 점수가 없다") {
                val ranking = Ranking.of(scanCount = 600, reviewCount = 0, uniqueReviewedFoodCount = 0)

                ranking.score shouldBe 1200
                ranking.tier shouldBe RankingTier.KOREAN_AT_HEART
                ranking.tier.level shouldBe 7
                ranking.nextTier shouldBe null
                ranking.pointsToNext shouldBe null
            }
        }
    }

    given("등급 안정 키") {
        `when`("등급을 노출할 때") {
            then("소문자 스네이크 케이스 키를 쓴다") {
                RankingTier.NEWCOMER.key shouldBe "newcomer"
                RankingTier.KFOOD_MASTER.key shouldBe "kfood_master"
                RankingTier.KOREAN_AT_HEART.key shouldBe "korean_at_heart"
            }
        }
    }
})
