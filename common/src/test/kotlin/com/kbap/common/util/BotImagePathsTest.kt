package com.kbap.common.util

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class BotImagePathsTest : BehaviorSpec({
    given("봇 전용 namespace 분류") {
        `when`("일반 업로드 경로나 비슷한 이름이면") {
            then("일반 경로의 기존 처리에 간섭하지 않는다") {
                listOf(
                    "prod/images/review/1_file.jpg",
                    "dev/images/feedback/0_file.jpg",
                    "prod/images/review-bot-other/file.jpg",
                    "prod/images/my-review-bot/file.jpg",
                    "prod/images/review/file-review-bot.jpg",
                    "prod/images/review/a+b%20c.jpg",
                ).forEach { BotImagePaths.isProtected(it) shouldBe false }
            }
        }
        `when`("namespace가 인코딩 또는 정규화 별칭으로 들어오면") {
            then("환경과 파일명에 관계없이 보호한다") {
                listOf(
                    "images/review-bot",
                    "prod/images//review-bot/key",
                    "staging/images/./review-bot/invalid",
                    "dev/images/x/../review-bot/key",
                    "prod/images/%72eview-bot/key",
                    "prod/images/%2572eview-bot/key",
                ).forEach { BotImagePaths.isProtected(it) shouldBe true }
            }
        }
    }
})
