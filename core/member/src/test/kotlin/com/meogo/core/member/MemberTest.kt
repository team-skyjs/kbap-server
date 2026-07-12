package com.meogo.core.member

import com.meogo.core.kernel.lang.CountryCode
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
                member.ranking.uniqueReviewedFoodCount shouldBe 0

                val ranking = member.ranking
                ranking.score shouldBe 0
                ranking.tier shouldBe RankingTier.NEWCOMER
                ranking.pointsToNext shouldBe 30
            }
        }
    }

    given("Member.ranking — 카운트에서 파생") {
        `when`("리뷰·고유 음식·스캔 카운트가 쌓인 회원이면") {
            then("세 카운트가 모두 점수에 반영된다") {
                val member = Member.reconstitute(
                    id = 1L,
                    identity = googleIdentity(),
                    profile = MemberProfile.empty(),
                    onboardingCompleted = true,
                    ranking = Ranking.of(scanCount = 9, reviewCount = 8, uniqueReviewedFoodCount = 6),
                )

                val ranking = member.ranking

                ranking.score shouldBe 128
                ranking.tier shouldBe RankingTier.EXPLORER
                ranking.pointsToNext shouldBe 52
            }
        }

        `when`("프로필을 갱신해도") {
            then("랭킹 카운트는 보존된다") {
                val member = Member.reconstitute(
                    id = 1L,
                    identity = googleIdentity(),
                    profile = MemberProfile.empty(),
                    onboardingCompleted = true,
                    ranking = Ranking.of(scanCount = 2, reviewCount = 0, uniqueReviewedFoodCount = 0),
                )

                val updated = member.updateProfile(MemberProfile.empty())

                updated.ranking.scanCount shouldBe 2
            }
        }
    }

    given("Member.updateProfile — 프로필 갱신(불변)") {
        `when`("새 프로필로 갱신하면") {
            then("새 인스턴스에 값이 반영되고 원본은 유지된다") {
                val member = Member.signUp(googleIdentity())
                val newProfile = MemberProfile.of(
                    nickname = "머고",
                    avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("PEANUT")),
                    spicinessPreference = 7,
                    countryCode = CountryCode.KR,
                    appLanguage = null,
                )

                val updated = member.updateProfile(newProfile)

                updated.profile shouldBe newProfile
                member.profile shouldBe MemberProfile.empty()
                updated.identity shouldBe member.identity
                updated.onboardingCompleted shouldBe member.onboardingCompleted
            }
        }
    }

    given("Member.completeOnboarding — 온보딩 전이") {
        `when`("미완료 회원이 완료 처리하면") {
            then("완료 상태로 전이한 새 인스턴스를 반환한다") {
                val member = Member.signUp(googleIdentity())

                val completed = member.completeOnboarding()

                completed.onboardingCompleted shouldBe true
                member.onboardingCompleted shouldBe false
            }
        }

        `when`("이미 완료된 회원이 재완료하면") {
            then("ONBOARDING_ALREADY_COMPLETED 예외를 던진다") {
                val completed = Member.reconstitute(
                    id = 1L,
                    identity = googleIdentity(),
                    profile = MemberProfile.empty(),
                    onboardingCompleted = true,
                )

                val e = shouldThrow<MemberException> { completed.completeOnboarding() }
                e.errorCode shouldBe MemberErrorCode.ONBOARDING_ALREADY_COMPLETED
            }
        }
    }
})
