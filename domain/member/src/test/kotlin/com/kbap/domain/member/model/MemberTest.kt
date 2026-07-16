package com.kbap.domain.member.model

import com.kbap.core.error.ErrorCode
import com.kbap.core.error.BusinessException
import com.kbap.core.lang.CountryCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MemberTest : BehaviorSpec({

    fun googleIdentity() = SocialIdentity(SocialProvider.GOOGLE, "google-sub-1", "user@gmail.com")

    fun submitOnboarding(member: Member) = member.completeOnboarding(
        nickname = "길동이",
        avoidanceSubstanceCodes = emptyList(),
        spicinessPreference = null,
        countryCode = "KR",
        appLanguage = "ko",
        profileImageUrl = null,
        allowedImageHosts = emptyList(),
    )

    given("Member.signUp — 최초 가입") {
        `when`("소셜 신원으로 가입하면") {
            then("온보딩 미완료·빈 프로필·해당 신원을 보유한다") {
                val member = Member.signUp(googleIdentity())

                member.onboardingCompleted shouldBe false
                member.profile shouldBe MemberProfile.empty()
                member.identity.providerUserId shouldBe "google-sub-1"
            }
        }

        `when`("가입 직후 랭킹을 보면") {
            then("스캔·리뷰·고유 음식 카운트가 모두 0이고 최하 등급이다") {
                val member = Member.signUp(googleIdentity())

                member.ranking.scanCount shouldBe 0
                member.ranking.reviewCount shouldBe 0
                member.ranking.tier shouldBe RankingTier.NEWCOMER
            }
        }
    }

    given("Member.updateProfile — 프로필 갱신") {
        `when`("새 프로필로 갱신하면") {
            then("프로필이 반영되고 신원·온보딩 상태는 유지된다") {
                val member = Member.signUp(googleIdentity())
                val newProfile = MemberProfile.of(
                    nickname = "머고",
                    avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("PEANUT")),
                    spicinessPreference = 7,
                    countryCode = CountryCode.KR,
                    appLanguage = null,
                )

                member.updateProfile(newProfile)

                member.profile shouldBe newProfile
                member.identity shouldBe googleIdentity()
                member.onboardingCompleted shouldBe false
            }
        }

        `when`("프로필을 갱신해도") {
            then("랭킹 카운트는 보존된다") {
                val member = Member.signUp(googleIdentity())
                member.scanCount = 2

                member.updateProfile(MemberProfile.empty())

                member.ranking.scanCount shouldBe 2
            }
        }
    }

    given("Member.completeOnboarding — 온보딩 전이") {
        `when`("미완료 회원이 온보딩 정보를 제출하면") {
            then("프로필이 반영되고 완료 상태로 전이한다") {
                val member = Member.signUp(googleIdentity())

                submitOnboarding(member)

                member.onboardingCompleted shouldBe true
                member.profile.nickname shouldBe "길동이"
                member.profile.countryCode shouldBe CountryCode.KR
            }
        }

        `when`("이미 완료된 회원이 재제출하면") {
            then("ONBOARDING_ALREADY_COMPLETED 예외를 던진다") {
                val member = Member.signUp(googleIdentity())
                submitOnboarding(member)

                val e = shouldThrow<BusinessException> { submitOnboarding(member) }
                e.errorCode shouldBe ErrorCode.ONBOARDING_ALREADY_COMPLETED
            }
        }

        `when`("맵기 선호를 생략(null)하고 온보딩하면") {
            then("맵기 선호가 미설정(-1)으로 저장된다") {
                val member = Member.signUp(googleIdentity())

                submitOnboarding(member)

                member.profile.spicinessPreference shouldBe -1
            }
        }

        `when`("맵기 선호로 -1 을 명시해 온보딩하면") {
            then("맵기 선호가 미설정(-1)으로 저장된다") {
                val member = Member.signUp(googleIdentity())

                member.completeOnboarding(
                    nickname = "길동이",
                    avoidanceSubstanceCodes = emptyList(),
                    spicinessPreference = -1,
                    countryCode = "KR",
                    appLanguage = "ko",
                    profileImageUrl = null,
                    allowedImageHosts = emptyList(),
                )

                member.profile.spicinessPreference shouldBe -1
            }
        }

        `when`("맵기 선호로 0~10 값을 명시해 온보딩하면") {
            then("그 값이 그대로 저장된다") {
                val member = Member.signUp(googleIdentity())

                member.completeOnboarding(
                    nickname = "길동이",
                    avoidanceSubstanceCodes = emptyList(),
                    spicinessPreference = 8,
                    countryCode = "KR",
                    appLanguage = "ko",
                    profileImageUrl = null,
                    allowedImageHosts = emptyList(),
                )

                member.profile.spicinessPreference shouldBe 8
            }
        }
    }

    given("배포 전 가입 회원(프로필에 맵기 5 저장, 온보딩 미완료)") {
        fun preDeployMember(): Member =
            Member.signUp(googleIdentity()).apply {
                profileJson = MemberProfileJson(spicinessPreference = 5)
            }

        `when`("맵기를 생략하고 온보딩하면") {
            then("맵기 선호가 미설정(-1)으로 저장된다") {
                val member = preDeployMember()

                submitOnboarding(member)

                member.profile.spicinessPreference shouldBe -1
            }
        }

        `when`("맵기 -1 을 명시하고 온보딩하면") {
            then("맵기 선호가 미설정(-1)으로 저장된다") {
                val member = preDeployMember()

                member.completeOnboarding(
                    nickname = "길동이",
                    avoidanceSubstanceCodes = emptyList(),
                    spicinessPreference = -1,
                    countryCode = "KR",
                    appLanguage = "ko",
                    profileImageUrl = null,
                    allowedImageHosts = emptyList(),
                )

                member.profile.spicinessPreference shouldBe -1
            }
        }
    }

    given("Member.withdraw — 탈퇴") {
        `when`("탈퇴 처리하면") {
            then("providerUid 가 삭제 마킹되고 엔티티가 DELETED 상태가 된다") {
                val member = Member.signUp(googleIdentity())

                member.withdraw()

                member.providerUid shouldBe "${Member.DELETED_PROVIDER_UID_PREFIX}${member.id}"
                member.isDeleted() shouldBe true
            }
        }
    }
})
