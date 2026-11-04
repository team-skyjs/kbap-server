package com.kbap.api.reviewbot

import com.kbap.common.port.llm.ReviewDraftRequest
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class ReviewBotStylesTest : BehaviorSpec({
    given("리뷰 봇 재시도 스타일") {
        `when`("어느 시드·어느 첫 스타일이든 restyle 하면") {
            then("첫 스타일과 다른 스타일을 뽑는다") {
                val collisions = ReviewBotStyles.ALL.flatMap { first ->
                    val request = ReviewDraftRequest("Bibimbap", "rice bowl", listOf("rice"), "English", 4, 80, first, 1, null)
                    (0L until 500L).filter { seed -> ReviewBotStyles.restyle(request, Random(seed)).style == first }.map { first to it }
                }

                collisions.shouldBeEmpty()
            }
        }

        `when`("제외할 스타일을 주고 여러 시드로 뽑으면") {
            then("그 스타일만 빠지고 나머지는 전부 나온다") {
                val except = ReviewBotStyles.ALL[0]

                (0L until 500L).map { ReviewBotStyles.pick(Random(it), except) }.toSet() shouldBe (ReviewBotStyles.ALL - except).toSet()
            }
        }
    }
})
