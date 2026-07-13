package com.kbap.domain.member

import com.kbap.core.error.ErrorCode
import com.kbap.core.error.BusinessException
import com.kbap.core.lang.CountryCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class MemberTest : BehaviorSpec({

    fun googleIdentity() = SocialIdentity(SocialProvider.GOOGLE, "google-sub-1", "user@gmail.com")

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
        `when`("미완료 회원이 완료 처리하면") {
            then("완료 상태로 전이한다") {
                val member = Member.signUp(googleIdentity())

                member.completeOnboarding()

                member.onboardingCompleted shouldBe true
            }
        }

        `when`("이미 완료된 회원이 재완료하면") {
            then("ONBOARDING_ALREADY_COMPLETED 예외를 던진다") {
                val member = Member.signUp(googleIdentity())
                member.completeOnboarding()

                val e = shouldThrow<BusinessException> { member.completeOnboarding() }
                e.errorCode shouldBe ErrorCode.ONBOARDING_ALREADY_COMPLETED
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
