package com.kbap.domain.member.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.kbap.core.error.BusinessException
import com.kbap.core.error.ErrorCode
import com.kbap.core.lang.CountryCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MemberProfileTest : BehaviorSpec({

    fun profile(spiciness: Int) = MemberProfile.of(
        nickname = null,
        avoidanceSubstanceCodes = emptySet(),
        spicinessPreference = spiciness,
        countryCode = null,
    )

    fun baseProfile() = MemberProfile.of(
        nickname = "머고",
        avoidanceSubstanceCodes = emptySet(),
        spicinessPreference = 5,
        countryCode = CountryCode.KR,
    )

    given("MemberProfile 맵기 선호 범위") {
        `when`("0~10 경계값이면") {
            then("정상 생성된다") {
                profile(0).spicinessPreference shouldBe 0
                profile(10).spicinessPreference shouldBe 10
            }
        }

        `when`("-1(미설정)이면") {
            then("정상 생성되고 -1 을 보존한다") {
                profile(-1).spicinessPreference shouldBe -1
            }
        }

        `when`("-1 도 0~10 도 아닌 값이면") {
            then("예외를 던진다") {
                shouldThrow<IllegalArgumentException> { profile(-2) }
                shouldThrow<IllegalArgumentException> { profile(11) }
            }
        }
    }

    given("MemberProfile.empty — 가입 직후 기본 프로필") {
        `when`("빈 프로필을 만들면") {
            then("맵기 선호는 미설정(-1), 기피성분은 빈 셋이다") {
                MemberProfile.empty().spicinessPreference shouldBe -1
                MemberProfile.empty().avoidanceSubstanceCodes shouldBe emptySet()
            }
        }
    }

    given("MemberProfileJson 역직렬화 — 레거시 회원(맵기 키 부재)") {
        `when`("spicinessPreference 키가 없는 JSON 을 읽으면") {
            then("맵기 선호가 미설정(-1)으로 해석된다") {
                val json = jacksonObjectMapper()
                    .readValue<MemberProfileJson>("""{"avoidanceSubstanceCodes":[]}""")

                json.toDomain(null).spicinessPreference shouldBe -1
            }
        }

        `when`("spicinessPreference 에 5 가 저장돼 있으면") {
            then("기존 값 5 를 그대로 읽는다") {
                val json = jacksonObjectMapper()
                    .readValue<MemberProfileJson>("""{"spicinessPreference":5}""")

                json.toDomain(null).spicinessPreference shouldBe 5
            }
        }
    }

    given("MemberProfileJson 역직렬화 — 폐기된 키가 남은 레거시 회원") {
        `when`("더 이상 쓰지 않는 appLanguage 키가 저장돼 있으면") {
            then("예외 없이 무시하고 나머지 값을 읽는다") {
                val json = ObjectMapper().registerKotlinModule().readValue<MemberProfileJson>(
                    """{"appLanguage":"ko","spicinessPreference":5,"countryCode":"KR"}""",
                )

                json.toDomain("머고").spicinessPreference shouldBe 5
                json.toDomain("머고").countryCode shouldBe CountryCode.KR
            }
        }
    }

    given("MemberProfile.updatedWith — 맵기 선호 부분 수정") {
        `when`("맵기 선호로 -1 을 명시 전송하면") {
            then("미설정(-1)으로 되돌린다") {
                baseProfile().updatedWith(spicinessPreference = -1)
                    .spicinessPreference shouldBe -1
            }
        }

        `when`("맵기 선호를 전송하지 않으면(null)") {
            then("기존 값을 유지한다") {
                baseProfile().updatedWith(spicinessPreference = null)
                    .spicinessPreference shouldBe 5
            }
        }

        `when`("미설정(-1) 상태에서 0~10 값을 전송하면") {
            then("그 값으로 교체한다") {
                val unset = MemberProfile.of(
                    nickname = null,
                    avoidanceSubstanceCodes = emptySet(),
                    spicinessPreference = -1,
                    countryCode = null,
                )

                unset.updatedWith(spicinessPreference = 7)
                    .spicinessPreference shouldBe 7
            }
        }

        `when`("맵기 선호로 -1 도 0~10 도 아닌 값을 전송하면") {
            then("MEMBER-009 로 거절한다") {
                listOf(-2, 11).forEach { invalid ->
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
                    avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("PEANUT"), AvoidanceSubstanceCodeRef("MILK")),
                    spicinessPreference = 3,
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
                    avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("PEANUT")),
                    spicinessPreference = 2,
                    countryCode = CountryCode.KR,
                )

                member.updateProfile(replacement)

                member.profile shouldBe replacement
                origin.nickname shouldBe null
                origin.spicinessPreference shouldBe -1
            }
        }
    }
})
