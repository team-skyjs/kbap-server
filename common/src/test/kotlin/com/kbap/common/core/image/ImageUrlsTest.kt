package com.kbap.common.core.image

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ImageUrlsTest : BehaviorSpec({
    given("ImageUrls.resolve") {
        `when`("ref 가 null 이면") {
            then("null 을 반환한다") {
                ImageUrls.resolve("https://cdn.example.com", null).shouldBeNull()
            }
        }

        `when`("ref 가 절대 URL 이면") {
            then("레거시 호환으로 그대로 반환한다") {
                ImageUrls.resolve("https://cdn.example.com", "https://legacy.com/a.jpg") shouldBe
                    "https://legacy.com/a.jpg"
                ImageUrls.resolve("https://cdn.example.com", "http://legacy.com/a.jpg") shouldBe
                    "http://legacy.com/a.jpg"
                ImageUrls.resolve("https://cdn.example.com", "HTTPS://legacy.com/a.jpg") shouldBe
                    "HTTPS://legacy.com/a.jpg"
            }
        }

        `when`("base 가 비어 있으면") {
            then("경로를 그대로 반환한다") {
                ImageUrls.resolve("", "profile-image/a.jpg") shouldBe "profile-image/a.jpg"
                ImageUrls.resolve("   ", "profile-image/a.jpg") shouldBe "profile-image/a.jpg"
            }
        }

        `when`("base 와 경로를 조합하면") {
            then("완전한 URL 을 반환한다") {
                ImageUrls.resolve("https://cdn.example.com", "profile-image/a.jpg") shouldBe
                    "https://cdn.example.com/profile-image/a.jpg"
            }

            then("슬래시 중복·누락을 정규화한다") {
                ImageUrls.resolve("https://cdn.example.com/", "/profile-image/a.jpg") shouldBe
                    "https://cdn.example.com/profile-image/a.jpg"
                ImageUrls.resolve("https://cdn.example.com/", "profile-image/a.jpg") shouldBe
                    "https://cdn.example.com/profile-image/a.jpg"
                ImageUrls.resolve("https://cdn.example.com", "/profile-image/a.jpg") shouldBe
                    "https://cdn.example.com/profile-image/a.jpg"
            }
        }

        `when`("base 설정만 바꾸면") {
            then("같은 경로가 새 도메인으로 조합된다") {
                val path = "food/2026/07/18/1/uuid.jpg"
                ImageUrls.resolve("https://cdn-old.example.com", path) shouldBe
                    "https://cdn-old.example.com/food/2026/07/18/1/uuid.jpg"
                ImageUrls.resolve("https://cdn-new.example.com", path) shouldBe
                    "https://cdn-new.example.com/food/2026/07/18/1/uuid.jpg"
            }
        }
    }
})
