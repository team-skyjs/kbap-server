package com.kbap.common.domain.member.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.member.model.CountryCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MemberProfileTest : BehaviorSpec({

    fun baseProfile() = MemberProfile.of(
        nickname = "머고",
        avoidanceSubstanceCodes = emptySet(),
        spicinessPreference = SpicinessPreference.MEDIUM,
        countryCode = CountryCode.KR,
    )

    given("MemberProfile.empty — 가입 직후 기본 프로필") {
        `when`("빈 프로필을 만들면") {
            then("맵기 선호는 미설정(SKIP), 기피성분은 빈 셋이다") {
                MemberProfile.empty().spicinessPreference shouldBe SpicinessPreference.SKIP
                MemberProfile.empty().avoidanceSubstanceCodes shouldBe emptySet()
            }
        }
    }

    given("MemberProfileJson 역직렬화 — 레거시 회원(맵기 키 부재)") {
        `when`("spicinessPreference 키가 없는 JSON 을 읽으면") {
            then("맵기 선호가 미설정(SKIP)으로 해석된다") {
                val json = jacksonObjectMapper()
                    .readValue<MemberProfileJson>("""{"avoidanceSubstanceCodes":[]}""")

                json.toDomain(null).spicinessPreference shouldBe SpicinessPreference.SKIP
            }
        }

        `when`("spicinessPreference 에 단계 문자열이 저장돼 있으면") {
            then("해당 단계로 읽는다") {
                val json = jacksonObjectMapper()
                    .readValue<MemberProfileJson>("""{"spicinessPreference":"HOT"}""")

                json.toDomain(null).spicinessPreference shouldBe SpicinessPreference.HOT
            }
        }

        `when`("이관되지 않은 정수 값이 저장돼 있으면") {
            then("조용히 흡수하지 않고 역직렬화가 실패한다") {
                shouldThrowAny {
                    jacksonObjectMapper().readValue<MemberProfileJson>("""{"spicinessPreference":5}""")
                }
            }
        }
    }

    given("MemberProfileJson 역직렬화 — 폐기된 키가 남은 레거시 회원") {
        `when`("더 이상 쓰지 않는 appLanguage 키가 저장돼 있으면") {
            then("예외 없이 무시하고 나머지 값을 읽는다") {
                val json = ObjectMapper().registerKotlinModule().readValue<MemberProfileJson>(
                    """{"appLanguage":"ko","spicinessPreference":"MEDIUM","countryCode":"KR"}""",
                )

                json.toDomain("머고").spicinessPreference shouldBe SpicinessPreference.MEDIUM
                json.toDomain("머고").countryCode shouldBe CountryCode.KR
            }
        }
    }

    given("MemberProfileJson 직렬화 — 저장 표현") {
        `when`("프로필을 JSON 으로 쓰면") {
            then("맵기 선호가 단계 이름 문자열로 저장된다") {
                val written = jacksonObjectMapper()
                    .writeValueAsString(MemberProfileJson(spicinessPreference = SpicinessPreference.EXTREME))

                written.contains("\"spicinessPreference\":\"EXTREME\"") shouldBe true
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
                    avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("PEANUT"), AvoidanceSubstanceCodeRef("MILK")),
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
                    avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("PEANUT")),
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
})
