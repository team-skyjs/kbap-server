package com.kbap.domain.research.ensemble

import com.kbap.domain.research.input.CandidateSubstance
import com.kbap.domain.research.input.ScoringFood
import com.kbap.domain.research.parse.ModelScoring
import com.kbap.domain.research.parse.SubstanceJudgement

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class ConsensusEnsembleAggregatorTest : BehaviorSpec({

    val aggregator = ConsensusEnsembleAggregator()

    val foods = listOf(ScoringFood(foodId = 1L, koreanName = "비빔밥"))
    val candidates = listOf(
        CandidateSubstance(code = "EGG", koreanLabel = "계란"),
        CandidateSubstance(code = "MILK", koreanLabel = "우유"),
        CandidateSubstance(code = "WHEAT", koreanLabel = "밀"),
    )

    fun modelJudging(vararg judgements: SubstanceJudgement): ModelScoring =
        ModelScoring(included = mapOf(1L to judgements.toList()))

    fun List<FoodInclusionScore>.forCode(code: String): FoodInclusionScore =
        first { it.substanceCode == code }

    given("골든 케이스 — 비빔밥 EGG 를 3모델이 score[2,1,2]·prob[90,70,80] 로 판단") {
        val perModel = listOf(
            modelJudging(SubstanceJudgement(code = "EGG", score = 2, probability = 90)),
            modelJudging(SubstanceJudgement(code = "EGG", score = 1, probability = 70)),
            modelJudging(SubstanceJudgement(code = "EGG", score = 2, probability = 80)),
        )

        `when`("종합하면") {
            val result = aggregator.aggregate(foods, candidates, perModel).first { it.foodId == 1L }
            val egg = result.scores.forCode("EGG")

            then("EGG inclusionConfidence 는 74 다") {
                egg.inclusionConfidence shouldBe 74
            }
            then("agreementFactor 는 0.9 다") {
                egg.agreementFactor shouldBe 0.9
            }
            then("avgScore 는 약 1.667 이다") {
                egg.avgScore shouldBe (1.6667 plusOrMinus 0.001)
            }
            then("avgProbability 는 80.0 이다") {
                egg.avgProbability shouldBe (80.0 plusOrMinus 0.001)
            }
        }
    }

    given("3모델 score 가 모두 동일 [2,2,2]") {
        val perModel = listOf(
            modelJudging(SubstanceJudgement(code = "EGG", score = 2, probability = 90)),
            modelJudging(SubstanceJudgement(code = "EGG", score = 2, probability = 90)),
            modelJudging(SubstanceJudgement(code = "EGG", score = 2, probability = 90)),
        )

        `when`("종합하면") {
            val egg = aggregator.aggregate(foods, candidates, perModel)
                .first { it.foodId == 1L }.scores.forCode("EGG")

            then("agreementFactor 는 1.0 이다") {
                egg.agreementFactor shouldBe 1.0
            }
        }
    }

    given("3모델 score 가 2종 [2,1,2]") {
        val perModel = listOf(
            modelJudging(SubstanceJudgement(code = "EGG", score = 2, probability = 80)),
            modelJudging(SubstanceJudgement(code = "EGG", score = 1, probability = 80)),
            modelJudging(SubstanceJudgement(code = "EGG", score = 2, probability = 80)),
        )

        `when`("종합하면") {
            val egg = aggregator.aggregate(foods, candidates, perModel)
                .first { it.foodId == 1L }.scores.forCode("EGG")

            then("agreementFactor 는 0.9 이다") {
                egg.agreementFactor shouldBe 0.9
            }
        }
    }

    given("3모델 score 가 3종 [0,1,2]") {
        val perModel = listOf(
            modelJudging(SubstanceJudgement(code = "EGG", score = 1, probability = 80)),
            modelJudging(SubstanceJudgement(code = "EGG", score = 2, probability = 80)),
            ModelScoring(included = emptyMap()),
        )

        `when`("한 모델이 EGG 를 미판단하면 그 모델은 score0·prob1 로 평균 반영된다") {
            val egg = aggregator.aggregate(foods, candidates, perModel)
                .first { it.foodId == 1L }.scores.forCode("EGG")

            then("agreementFactor 는 3종(1,2,0)이라 0.75 다") {
                egg.agreementFactor shouldBe 0.75
            }
        }
    }

    given("한 성분을 2개 모델만 판단하고 1개 모델은 미판단") {
        val perModel = listOf(
            modelJudging(SubstanceJudgement(code = "EGG", score = 2, probability = 90)),
            modelJudging(SubstanceJudgement(code = "EGG", score = 2, probability = 90)),
            ModelScoring(included = emptyMap()),
        )

        `when`("종합하면 미판단 모델은 score0·prob1 로 보정된다") {
            val egg = aggregator.aggregate(foods, candidates, perModel)
                .first { it.foodId == 1L }.scores.forCode("EGG")

            then("avgScore 는 (2+2+0)/3 이다") {
                egg.avgScore shouldBe ((2 + 2 + 0) / 3.0 plusOrMinus 0.001)
            }
            then("avgProbability 는 (90+90+1)/3 이다") {
                egg.avgProbability shouldBe ((90 + 90 + 1) / 3.0 plusOrMinus 0.001)
            }
        }
    }

    given("어떤 후보를 3모델 모두 미판단") {
        val perModel = listOf(
            ModelScoring(included = emptyMap()),
            ModelScoring(included = emptyMap()),
            ModelScoring(included = emptyMap()),
        )

        `when`("종합하면 누락 보정으로 score0·prob1·agreement1.0 이 되고 floor clamp 된다") {
            val result = aggregator.aggregate(foods, candidates, perModel).first { it.foodId == 1L }

            then("모든 후보의 inclusionConfidence 는 최소값 1 이다") {
                result.scores.forEach { it.inclusionConfidence shouldBe 1 }
            }
        }
    }

    given("후보 성분 목록") {
        val perModel = listOf(
            modelJudging(SubstanceJudgement(code = "EGG", score = 2, probability = 90)),
            modelJudging(SubstanceJudgement(code = "EGG", score = 1, probability = 70)),
            modelJudging(SubstanceJudgement(code = "EGG", score = 2, probability = 80)),
        )

        `when`("종합하면") {
            val result = aggregator.aggregate(foods, candidates, perModel).first { it.foodId == 1L }

            then("각 음식 scores 크기는 후보 개수와 같다") {
                result.scores.size shouldBe candidates.size
            }
            then("scores 는 후보 순서대로 나온다") {
                result.scores.map { it.substanceCode } shouldBe candidates.map { it.code }
            }
        }
    }

    given("perModel 이 정확히 3개가 아님") {
        `when`("2개면") {
            then("IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    aggregator.aggregate(
                        foods,
                        candidates,
                        listOf(ModelScoring(included = emptyMap()), ModelScoring(included = emptyMap())),
                    )
                }
            }
        }
        `when`("4개면") {
            then("IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    aggregator.aggregate(
                        foods,
                        candidates,
                        List(4) { ModelScoring(included = emptyMap()) },
                    )
                }
            }
        }
    }
})
