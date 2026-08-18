package com.kbap.common.domain.member.model

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.CurrencyCode
import com.kbap.common.domain.ingredient.model.DietCategory
import com.kbap.common.domain.member.model.CountryCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MemberProfileTest : BehaviorSpec({

    fun baseProfile() = MemberProfile.of(
        nickname = "머고",
        avoidanceSubstanceCodes = emptySet(),
        spicinessPreference = SpicinessPreference.MEDIUM,
        countryCode = CountryCode.KR,
    )

    given("MemberProfile — diet 카테고리 복수 선택") {
        `when`("diet 2종을 지정하면") {
            then("집합으로 저장된다") {
                baseProfile().updatedWith(dietCategories = listOf("VEGAN", "GLUTEN_FREE"))
                    .dietCategories shouldBe setOf(DietCategory.VEGAN, DietCategory.GLUTEN_FREE)
            }
        }

        `when`("같은 diet 를 중복으로 지정하면") {
            then("한 번만 저장된다") {
                baseProfile().updatedWith(dietCategories = listOf("VEGAN", "VEGAN"))
                    .dietCategories shouldBe setOf(DietCategory.VEGAN)
            }
        }

        `when`("지원하지 않는 diet 값을 지정하면") {
            then("MEMBER-011 로 거절한다") {
                val e = shouldThrow<BusinessException> {
                    baseProfile().updatedWith(dietCategories = listOf("KETO"))
                }
                e.errorCode shouldBe ErrorCode.INVALID_DIET_CATEGORY
            }
        }

        `when`("diet 를 전송하지 않으면(null)") {
            then("기존 선택을 유지한다") {
                baseProfile().updatedWith(dietCategories = listOf("MUSLIM"))
                    .updatedWith(dietCategories = null)
                    .dietCategories shouldBe setOf(DietCategory.MUSLIM)
            }
        }

        `when`("빈 목록을 전송하면") {
            then("전부 해제된다") {
                baseProfile().updatedWith(dietCategories = listOf("MUSLIM"))
                    .updatedWith(dietCategories = emptyList())
                    .dietCategories shouldBe emptySet<DietCategory>()
            }
        }

        `when`("빈 프로필을 만들면") {
            then("diet 는 빈 집합이다") {
                MemberProfile.empty().dietCategories shouldBe emptySet<DietCategory>()
            }
        }
    }

    given("MemberProfile.empty — 가입 직후 기본 프로필") {
        `when`("빈 프로필을 만들면") {
            then("맵기 선호는 미설정(SKIP), 기피성분은 빈 셋이다") {
                MemberProfile.empty().spicinessPreference shouldBe SpicinessPreference.SKIP
                MemberProfile.empty().avoidanceSubstanceCodes shouldBe emptySet()
            }
        }
    }

    given("MemberProfile.updatedWith — 맵기 선호 부분 수정") {
        `when`("맵기 선호로 SKIP 을 명시 전송하면") {
            then("미설정(SKIP)으로 되돌린다") {
                baseProfile().updatedWith(spicinessPreference = "SKIP")
                    .spicinessPreference shouldBe SpicinessPreference.SKIP
            }
        }

        `when`("맵기 선호를 전송하지 않으면(null)") {
            then("기존 값을 유지한다") {
                baseProfile().updatedWith(spicinessPreference = null)
                    .spicinessPreference shouldBe SpicinessPreference.MEDIUM
            }
        }

        `when`("미설정(SKIP) 상태에서 단계 문자열을 전송하면") {
            then("그 단계로 교체한다") {
                MemberProfile.empty().updatedWith(spicinessPreference = "MILD")
                    .spicinessPreference shouldBe SpicinessPreference.MILD
            }
        }

        `when`("6단계에 없는 값을 전송하면") {
            then("MEMBER-009 로 거절한다") {
                listOf("SUPER_HOT", "5", "-1", "hot").forEach { invalid ->
                    val e = shouldThrow<BusinessException> {
                        baseProfile().updatedWith(spicinessPreference = invalid)
                    }
                    e.errorCode shouldBe ErrorCode.INVALID_SPICINESS_PREFERENCE
                }
            }
        }
    }

    given("MemberProfile.updatedWith — 프로필 사진 경로(2분법)") {
        `when`("CDN 도메인 없는 경로를 전송하면") {
            then("경로 그대로 저장한다") {
                baseProfile().updatedWith(profileImageUrl = "profile-image/2026/07/18/1/uuid.jpg")
                    .profileImageUrl shouldBe "profile-image/2026/07/18/1/uuid.jpg"
            }
        }

        `when`("전송하지 않으면(null)") {
            then("기존 사진을 유지한다") {
                val withImage = baseProfile().updatedWith(profileImageUrl = "profile-image/a.jpg")

                withImage.updatedWith(profileImageUrl = null)
                    .profileImageUrl shouldBe "profile-image/a.jpg"
            }
        }

        `when`("빈 문자열을 전송하면") {
            then("MEMBER-008 로 거절한다") {
                listOf("", " ", "   ").forEach { blank ->
                    val e = shouldThrow<BusinessException> {
                        baseProfile().updatedWith(profileImageUrl = blank)
                    }
                    e.errorCode shouldBe ErrorCode.INVALID_PROFILE_IMAGE_URL
                }
            }
        }

        `when`("전체 URL 을 전송하면") {
            then("MEMBER-008 로 거절한다") {
                listOf(
                    "https://cdn.example.com/a.jpg",
                    "http://cdn.example.com/a.jpg",
                    "HTTPS://cdn.example.com/a.jpg",
                ).forEach { absoluteUrl ->
                    val e = shouldThrow<BusinessException> {
                        baseProfile().updatedWith(profileImageUrl = absoluteUrl)
                    }
                    e.errorCode shouldBe ErrorCode.INVALID_PROFILE_IMAGE_URL
                }
            }
        }

        `when`("512자를 초과하는 경로를 전송하면") {
            then("MEMBER-008 로 거절한다") {
                val e = shouldThrow<BusinessException> {
                    baseProfile().updatedWith(profileImageUrl = "a".repeat(513))
                }
                e.errorCode shouldBe ErrorCode.INVALID_PROFILE_IMAGE_URL
            }
        }
    }

    given("MemberProfile 값 보존") {
        `when`("닉네임·기피성분·국가를 담으면") {
            then("그대로 보존한다") {
                val profile = MemberProfile.of(
                    nickname = "머고",
                    avoidanceSubstanceCodes = setOf(AvoidedIngredientCodeRef("PEANUT"), AvoidedIngredientCodeRef("MILK")),
                    spicinessPreference = SpicinessPreference.MILD,
                    countryCode = CountryCode.KR,
                )

                profile.nickname shouldBe "머고"
                profile.avoidanceSubstanceCodes.map { it.value }.toSet() shouldBe setOf("PEANUT", "MILK")
                profile.countryCode shouldBe CountryCode.KR
            }
        }
    }

    given("프로필 수정 — 통째 교체") {
        `when`("회원의 프로필을 새 프로필로 교체하면") {
            then("회원이 새 프로필을 갖고 프로필 값 객체 자체는 그대로다") {
                val origin = MemberProfile.empty()
                val member = Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "sub-1", null))
                val replacement = MemberProfile.of(
                    nickname = "머고",
                    avoidanceSubstanceCodes = setOf(AvoidedIngredientCodeRef("PEANUT")),
                    spicinessPreference = SpicinessPreference.MILD,
                    countryCode = CountryCode.KR,
                )

                member.updateProfile(replacement)

                member.profile shouldBe replacement
                origin.nickname shouldBe null
                origin.spicinessPreference shouldBe SpicinessPreference.SKIP
            }
        }
    }

    given("온보딩 — 국가 기준 통화 자동 지정") {
        fun onboardedMember(countryCode: String): Member {
            val member = Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "sub-currency-$countryCode", null))
            member.completeOnboarding(
                nickname = "머고",
                avoidanceSubstanceCodes = emptyList(),
                spicinessPreference = "MEDIUM",
                countryCode = countryCode,
                profileImageUrl = "profile/default.webp",
            )
            return member
        }

        `when`("일본으로 온보딩하면") {
            then("통화가 엔으로 지정된다") {
                onboardedMember("JP").profile.currency shouldBe CurrencyCode.JPY
            }
        }

        `when`("유로존 국가로 온보딩하면") {
            then("통화가 유로로 지정된다") {
                onboardedMember("FR").profile.currency shouldBe CurrencyCode.EUR
            }
        }

        `when`("취급 통화 밖 통화를 쓰는 국가로 온보딩하면") {
            then("통화가 달러로 대체 지정된다") {
                onboardedMember("NG").profile.currency shouldBe CurrencyCode.USD
            }
        }

        `when`("온보딩 전이면") {
            then("통화가 비어 있다") {
                Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "sub-currency-none", null))
                    .profile.currency shouldBe null
            }
        }
    }

    given("MemberProfile.updatedWith — 통화 부분 수정") {
        fun profileWith(currency: CurrencyCode?) = MemberProfile.of(
            nickname = "머고",
            avoidanceSubstanceCodes = emptySet(),
            spicinessPreference = SpicinessPreference.MEDIUM,
            countryCode = CountryCode.KR,
            currency = currency,
        )

        `when`("지원하는 통화를 전송하면") {
            then("그 통화로 교체된다") {
                profileWith(CurrencyCode.KRW).updatedWith(currency = "JPY").currency shouldBe CurrencyCode.JPY
            }
        }

        `when`("통화를 전송하지 않으면") {
            then("기존 통화가 유지된다") {
                profileWith(CurrencyCode.JPY).updatedWith(nickname = "새이름").currency shouldBe CurrencyCode.JPY
            }
        }

        `when`("지원하지 않는 통화를 전송하면") {
            then("MEMBER-010 으로 거절한다") {
                val e = shouldThrow<BusinessException> { profileWith(CurrencyCode.KRW).updatedWith(currency = "XAU") }
                e.errorCode shouldBe ErrorCode.INVALID_CURRENCY_CODE
            }
        }

        `when`("대소문자·공백이 다른 값을 전송하면") {
            then("정규화하지 않고 거절한다") {
                shouldThrow<BusinessException> { profileWith(CurrencyCode.KRW).updatedWith(currency = "jpy") }
                shouldThrow<BusinessException> { profileWith(CurrencyCode.KRW).updatedWith(currency = " JPY ") }
            }
        }
    }

    given("국가와 통화의 독립성") {
        val profile = MemberProfile.of(
            nickname = "머고",
            avoidanceSubstanceCodes = emptySet(),
            spicinessPreference = SpicinessPreference.MEDIUM,
            countryCode = CountryCode.JP,
            currency = CurrencyCode.JPY,
        )

        `when`("국가만 미국으로 바꾸면") {
            then("국가만 바뀌고 통화는 그대로다") {
                val updated = profile.updatedWith(countryCode = "US")

                updated.countryCode shouldBe CountryCode.US
                updated.currency shouldBe CurrencyCode.JPY
            }
        }

        `when`("국가와 통화를 함께 보내면") {
            then("둘 다 요청대로 저장된다") {
                val updated = profile.updatedWith(countryCode = "US", currency = "KRW")

                updated.countryCode shouldBe CountryCode.US
                updated.currency shouldBe CurrencyCode.KRW
            }
        }

        `when`("통화만 바꾸면") {
            then("통화만 바뀌고 국가는 그대로다") {
                val updated = profile.updatedWith(currency = "KRW")

                updated.countryCode shouldBe CountryCode.JP
                updated.currency shouldBe CurrencyCode.KRW
            }
        }
    }
})
