package com.kbap.api.reviewbot

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.random.Random

class ReviewBotPhotoPoolTest : BehaviorSpec({

    given("사진 풀 키") {
        `when`("korean_name 을 해시하면") {
            then("sha256 의 앞 12자리 소문자 hex 다 — 매핑 표 없이 업로더(예진)와 같은 값") {
                ReviewBotPhotoPool.keyOf("후라이드치킨") shouldBe "6a2c1605affd"
                ReviewBotPhotoPool.keyOf("비빔밥") shouldBe "574c26d16913"
            }
        }
        `when`("저장 접두를 만들면") {
            then("key-prefix 가 있으면 앞에, 없으면 images/review-bot/ 만") {
                ReviewBotPhotoPool.poolPrefix("prod") shouldBe "prod/images/review-bot/"
                ReviewBotPhotoPool.poolPrefix("/local/") shouldBe "local/images/review-bot/"
                ReviewBotPhotoPool.poolPrefix("") shouldBe "images/review-bot/"
            }
        }
        `when`("오브젝트 경로를 해석하면") {
            then("<접두>images/review-bot/<12 hex>/<n>.webp 만 풀로 본다") {
                ReviewBotPhotoPool.keyOfPath("prod/images/review-bot/6a2c1605affd/1.webp") shouldBe "6a2c1605affd"
                ReviewBotPhotoPool.keyOfPath("images/review-bot/6a2c1605affd/12.webp") shouldBe "6a2c1605affd"
                ReviewBotPhotoPool.keyOfPath("prod/images/review-bot/6a2c1605affd/1.png") shouldBe null
                ReviewBotPhotoPool.keyOfPath("prod/images/review-bot/notakey/1.webp") shouldBe null
                ReviewBotPhotoPool.keyOfPath("prod/images/review/2026/10/1_a.webp") shouldBe null
            }
        }
    }

    given("사진 조합 고르기") {
        val pool = listOf("p/1.webp", "p/2.webp", "p/3.webp")
        `when`("풀이 3장이면") {
            then("1~3장을 고르고 직전 조합과는 다르게 고른다") {
                val previous = setOf("p/1.webp", "p/2.webp", "p/3.webp")
                repeat(50) { seed ->
                    val picked = ReviewBotPhotoPool.pickCombination(pool, previous, Random(seed))
                    picked.size shouldBeInRange 1..3
                    picked.toSet() shouldNotBe previous
                    picked.toSet().size shouldBe picked.size
                }
            }
        }
        `when`("풀이 1장뿐이면") {
            then("직전과 같아도 그 1장을 쓴다(사진 없는 리뷰보다 반복이 낫다)") {
                ReviewBotPhotoPool.pickCombination(listOf("p/1.webp"), setOf("p/1.webp"), Random(1)) shouldContainExactly listOf("p/1.webp")
            }
        }
        `when`("직전 조합이 없으면") {
            then("풀 안의 경로만 고른다") {
                val picked = ReviewBotPhotoPool.pickCombination(pool, emptySet(), Random(7))
                pool.containsAll(picked) shouldBe true
            }
        }
    }
})
