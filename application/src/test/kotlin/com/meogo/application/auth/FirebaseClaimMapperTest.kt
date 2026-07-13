package com.meogo.application.auth

import com.meogo.domain.member.SocialProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class FirebaseClaimMapperTest : BehaviorSpec({

    fun claims(
        signInProvider: String,
        identities: Map<String, Any>,
        email: String? = "user@gmail.com",
        uid: String = "firebase-uid-should-not-be-stored",
    ): Map<String, Any> = buildMap {
        put("sub", uid)
        put("firebase", mapOf("sign_in_provider" to signInProvider, "identities" to identities))
        email?.let { put("email", it) }
    }

    given("구글 로그인 클레임") {
        `when`("매핑하면") {
            then("GOOGLE 과 구글이 발급한 원본 sub 를 신원으로 만든다") {
                val identity = FirebaseClaimMapper.toSocialIdentity(
                    claims(
                        signInProvider = "google.com",
                        identities = mapOf("google.com" to listOf("google-original-sub")),
                    ),
                )

                identity.provider shouldBe SocialProvider.GOOGLE
                identity.providerUserId shouldBe "google-original-sub"
                identity.email shouldBe "user@gmail.com"
            }
        }

        `when`("최상위 sub(Firebase uid)와 비교하면") {
            then("Firebase uid 는 저장하지 않는다") {
                val identity = FirebaseClaimMapper.toSocialIdentity(
                    claims(
                        signInProvider = "google.com",
                        identities = mapOf("google.com" to listOf("google-original-sub")),
                        uid = "firebase-uid-should-not-be-stored",
                    ),
                )

                (identity.providerUserId != "firebase-uid-should-not-be-stored") shouldBe true
            }
        }
    }

    given("애플 로그인 클레임") {
        `when`("이메일 가리기 릴레이 주소를 담아 매핑하면") {
            then("APPLE 과 릴레이 주소를 그대로 보존한다") {
                val identity = FirebaseClaimMapper.toSocialIdentity(
                    claims(
                        signInProvider = "apple.com",
                        identities = mapOf("apple.com" to listOf("apple-original-sub")),
                        email = "abc123@privaterelay.appleid.com",
                    ),
                )

                identity.provider shouldBe SocialProvider.APPLE
                identity.providerUserId shouldBe "apple-original-sub"
                identity.email shouldBe "abc123@privaterelay.appleid.com"
            }
        }

        `when`("이메일이 없는 클레임을 매핑하면") {
            then("이메일 없이 신원을 만든다") {
                val identity = FirebaseClaimMapper.toSocialIdentity(
                    claims(
                        signInProvider = "apple.com",
                        identities = mapOf("apple.com" to listOf("apple-original-sub")),
                        email = null,
                    ),
                )

                identity.email.shouldBeNull()
            }
        }
    }

    given("지원하지 않는 provider") {
        `when`("매핑하면") {
            then("UNSUPPORTED_PROVIDER 예외를 던진다") {
                val e = shouldThrow<AuthException> {
                    FirebaseClaimMapper.toSocialIdentity(
                        claims(
                            signInProvider = "facebook.com",
                            identities = mapOf("facebook.com" to listOf("fb-sub")),
                        ),
                    )
                }

                e.errorCode shouldBe AuthErrorCode.UNSUPPORTED_PROVIDER
            }
        }
    }

    given("훼손된 클레임") {
        `when`("identities 에 해당 provider 원소가 없으면") {
            then("INVALID_SOCIAL_TOKEN 예외를 던진다") {
                val e = shouldThrow<AuthException> {
                    FirebaseClaimMapper.toSocialIdentity(
                        claims(signInProvider = "google.com", identities = emptyMap()),
                    )
                }

                e.errorCode shouldBe AuthErrorCode.INVALID_SOCIAL_TOKEN
            }
        }

        `when`("firebase 클레임 자체가 없으면") {
            then("INVALID_SOCIAL_TOKEN 예외를 던진다") {
                val e = shouldThrow<AuthException> {
                    FirebaseClaimMapper.toSocialIdentity(mapOf("sub" to "uid"))
                }

                e.errorCode shouldBe AuthErrorCode.INVALID_SOCIAL_TOKEN
            }
        }

        `when`("provider 원소가 빈 배열이면") {
            then("INVALID_SOCIAL_TOKEN 예외를 던진다") {
                val e = shouldThrow<AuthException> {
                    FirebaseClaimMapper.toSocialIdentity(
                        claims(signInProvider = "google.com", identities = mapOf("google.com" to emptyList<String>())),
                    )
                }

                e.errorCode shouldBe AuthErrorCode.INVALID_SOCIAL_TOKEN
            }
        }
    }
})
