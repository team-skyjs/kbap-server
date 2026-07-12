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
            then("모든 카운트가 0이고 최하 등급이다") {
                val member = Member.signUp(googleIdentity())

                member.scanCount shouldBe 0

                val ranking = member.ranking()
                ranking.score shouldBe 0
                ranking.tier shouldBe RankingTier.NEWCOMER
                ranking.pointsToNext shouldBe 30
            }
        }
    }

    given("Member.recordScan — 메뉴판 스캔 카운트업(불변)") {
        `when`("메뉴판을 한 번 스캔하면") {
            then("새 인스턴스의 스캔 횟수가 1 오르고 원본은 그대로다") {
                val member = Member.signUp(googleIdentity())

                val scanned = member.recordScan()

                scanned.scanCount shouldBe 1
                member.scanCount shouldBe 0
            }
        }

        `when`("메뉴판을 여러 번 스캔하면") {
            then("스캔 횟수만큼 점수가 오른다") {
                val member = (1..40).fold(Member.signUp(googleIdentity())) { m, _ -> m.recordScan() }

                member.scanCount shouldBe 40

                val ranking = member.ranking()
                ranking.score shouldBe 80
                ranking.tier shouldBe RankingTier.EXPLORER
                ranking.nextTier shouldBe RankingTier.REGULAR
                ranking.pointsToNext shouldBe 100
            }
        }

        `when`("스캔 이후 프로필을 갱신해도") {
            then("스캔 횟수는 보존된다") {
                val member = Member.signUp(googleIdentity()).recordScan().recordScan()

                val updated = member.updateProfile(MemberProfile.empty())

                updated.scanCount shouldBe 2
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
