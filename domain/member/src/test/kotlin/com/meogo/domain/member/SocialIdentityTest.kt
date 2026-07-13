package com.meogo.domain.member

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class SocialIdentityTest : BehaviorSpec({

    given("SocialIdentity 생성") {
        `when`("정상 값으로 생성하면") {
            then("provider·providerUserId·email 을 그대로 보존한다") {
                val identity = SocialIdentity(
                    provider = SocialProvider.GOOGLE,
                    providerUserId = "google-sub-123",
                    email = "user@gmail.com",
                )

                identity.provider shouldBe SocialProvider.GOOGLE
                identity.providerUserId shouldBe "google-sub-123"
                identity.email shouldBe "user@gmail.com"
            }
        }

        `when`("email 이 null 이면") {
            then("부재를 허용해 생성된다") {
                val identity = SocialIdentity(
                    provider = SocialProvider.APPLE,
                    providerUserId = "apple-sub-456",
                    email = null,
                )

                identity.email shouldBe null
            }
        }

        `when`("providerUserId 가 blank 이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    SocialIdentity(SocialProvider.GOOGLE, "  ", "user@gmail.com")
                }
            }
        }

        `when`("providerUserId 에 앞뒤 공백이 있으면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> {
                    SocialIdentity(SocialProvider.GOOGLE, " sub ", null)
                }
            }
        }
    }
})
