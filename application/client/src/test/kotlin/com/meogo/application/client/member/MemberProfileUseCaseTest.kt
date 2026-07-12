package com.meogo.application.client.member

import com.meogo.application.client.member.dto.MemberProfileInput
import com.meogo.application.client.member.dto.ProfileUpdateInput
import com.meogo.core.kernel.lang.CountryCode
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.member.AvoidanceSubstanceCodeRef
import com.meogo.core.member.Member
import com.meogo.core.member.MemberErrorCode
import com.meogo.core.member.MemberException
import com.meogo.core.member.MemberProfile
import com.meogo.core.member.MemberRepository
import com.meogo.core.member.Ranking
import com.meogo.core.member.SocialIdentity
import com.meogo.core.member.SocialProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class MemberProfileUseCaseTest : BehaviorSpec({

    fun profileUseCase(repository: FakeMemberRepository) = MemberProfileUseCase(repository)

    fun member(
        id: Long,
        onboardingCompleted: Boolean = false,
        profile: MemberProfile = MemberProfile.empty(),
        scanCount: Int = 0,
    ): Member =
        Member.reconstitute(
            id = id,
            identity = SocialIdentity(SocialProvider.GOOGLE, "google-sub-$id", "user$id@gmail.com"),
            profile = profile,
            onboardingCompleted = onboardingCompleted,
            ranking = Ranking.of(scanCount = scanCount, reviewCount = 0, uniqueReviewedFoodCount = 0),
        )

    fun input(
        memberId: Long = 1L,
        nickname: String = "길동이",
        avoidanceSubstanceCodes: List<String> = listOf("EGG", "MILK"),
        countryCode: String = "US",
        appLanguage: String = "en",
    ) = MemberProfileInput(
        memberId = memberId,
        nickname = nickname,
        avoidanceSubstanceCodes = avoidanceSubstanceCodes,
        countryCode = countryCode,
        appLanguage = appLanguage,
    )

    fun updateInput(
        memberId: Long = 1L,
        nickname: String? = null,
        avoidanceSubstanceCodes: List<String>? = null,
        countryCode: String? = null,
        appLanguage: String? = null,
    ) = ProfileUpdateInput(
        memberId = memberId,
        nickname = nickname,
        avoidanceSubstanceCodes = avoidanceSubstanceCodes,
        countryCode = countryCode,
        appLanguage = appLanguage,
    )

    given("온보딩 미완료 회원") {
        `when`("유효한 온보딩 정보를 제출하면") {
            then("프로필이 저장되고 온보딩이 완료로 전이된다") {
                val repo = FakeMemberRepository().apply { seed(member(1L)) }
                val useCase = profileUseCase(repo)

                useCase.completeOnboarding(input())

                val saved = repo.findById(1L)!!
                saved.onboardingCompleted shouldBe true
                saved.profile.nickname shouldBe "길동이"
                saved.profile.avoidanceSubstanceCodes.map { it.value }.toSet() shouldBe setOf("EGG", "MILK")
                saved.profile.countryCode shouldBe CountryCode.US
                saved.profile.appLanguage shouldBe LanguageCode.EN
            }
        }

        `when`("기존 맵기 선호도를 가진 회원이 제출하면") {
            then("맵기 선호도는 온보딩 입력에 없으므로 기존 값이 보존된다") {
                val existing = MemberProfile.of(
                    nickname = null,
                    avoidanceSubstanceCodes = emptySet(),
                    spicinessPreference = 8,
                    countryCode = null,
                    appLanguage = null,
                )
                val repo = FakeMemberRepository().apply { seed(member(1L, profile = existing)) }
                val useCase = profileUseCase(repo)

                useCase.completeOnboarding(input())

                repo.findById(1L)!!.profile.spicinessPreference shouldBe 8
            }
        }

        `when`("기피 음식을 하나도 제출하지 않으면(빈 목록)") {
            then("기피 성분 없이 정상 저장된다") {
                val repo = FakeMemberRepository().apply { seed(member(1L)) }
                val useCase = profileUseCase(repo)

                useCase.completeOnboarding(input(avoidanceSubstanceCodes = emptyList()))

                val saved = repo.findById(1L)!!
                saved.onboardingCompleted shouldBe true
                saved.profile.avoidanceSubstanceCodes.shouldBeEmpty()
            }
        }
    }

    given("이미 온보딩을 완료한 회원") {
        `when`("온보딩을 다시 제출하면") {
            then("ONBOARDING_ALREADY_COMPLETED 로 거절되고 프로필은 변하지 않는다") {
                val original = MemberProfile.of(
                    nickname = "원래닉",
                    avoidanceSubstanceCodes = emptySet(),
                    spicinessPreference = 5,
                    countryCode = CountryCode.KR,
                    appLanguage = LanguageCode.KO,
                )
                val repo = FakeMemberRepository().apply {
                    seed(member(1L, onboardingCompleted = true, profile = original))
                }
                val useCase = profileUseCase(repo)

                val e = shouldThrow<MemberException> { useCase.completeOnboarding(input()) }

                e.errorCode shouldBe MemberErrorCode.ONBOARDING_ALREADY_COMPLETED
                repo.findById(1L)!!.profile.nickname shouldBe "원래닉"
            }
        }
    }

    given("존재하지 않는 회원") {
        `when`("온보딩을 제출하면") {
            then("MEMBER_NOT_FOUND 로 거절된다") {
                val repo = FakeMemberRepository()
                val useCase = profileUseCase(repo)

                val e = shouldThrow<MemberException> { useCase.completeOnboarding(input(memberId = 99L)) }

                e.errorCode shouldBe MemberErrorCode.MEMBER_NOT_FOUND
            }
        }
    }

    given("입력 검증 — 위반 시 저장 없이 거절") {
        `when`("카탈로그 81종에 없는 기피 성분 코드를 포함하면") {
            then("INVALID_AVOIDANCE_SUBSTANCE_CODE 로 거절되고 저장되지 않는다") {
                val repo = FakeMemberRepository().apply { seed(member(1L)) }
                val useCase = profileUseCase(repo)

                val e = shouldThrow<OnboardingException> {
                    useCase.completeOnboarding(input(avoidanceSubstanceCodes = listOf("EGG", "NOT_A_CODE")))
                }

                e.errorCode shouldBe OnboardingErrorCode.INVALID_AVOIDANCE_SUBSTANCE_CODE
                val unchanged = repo.findById(1L)!!
                unchanged.onboardingCompleted shouldBe false
                unchanged.profile.nickname shouldBe null
            }
        }

        `when`("소문자 등 형식이 맞지 않는 성분 코드를 포함하면") {
            then("INVALID_AVOIDANCE_SUBSTANCE_CODE 로 거절된다") {
                val repo = FakeMemberRepository().apply { seed(member(1L)) }
                val useCase = profileUseCase(repo)

                val e = shouldThrow<OnboardingException> {
                    useCase.completeOnboarding(input(avoidanceSubstanceCodes = listOf("egg")))
                }

                e.errorCode shouldBe OnboardingErrorCode.INVALID_AVOIDANCE_SUBSTANCE_CODE
            }
        }

        `when`("지정 목록에 없는 국가 코드를 제출하면") {
            then("INVALID_COUNTRY_CODE 로 거절된다") {
                val repo = FakeMemberRepository().apply { seed(member(1L)) }
                val useCase = profileUseCase(repo)

                val e = shouldThrow<OnboardingException> {
                    useCase.completeOnboarding(input(countryCode = "ZZ"))
                }

                e.errorCode shouldBe OnboardingErrorCode.INVALID_COUNTRY_CODE
            }
        }

        listOf("fr", "EN", "ko-KR").forEach { badLang ->
            `when`("지원 10개국어 code 와 정확히 일치하지 않는 언어($badLang)를 제출하면") {
                then("UNSUPPORTED_APP_LANGUAGE 로 거절된다") {
                    val repo = FakeMemberRepository().apply { seed(member(1L)) }
                    val useCase = profileUseCase(repo)

                    val e = shouldThrow<OnboardingException> {
                        useCase.completeOnboarding(input(appLanguage = badLang))
                    }

                    e.errorCode shouldBe OnboardingErrorCode.UNSUPPORTED_APP_LANGUAGE
                }
            }
        }

        `when`("공백만 있는 닉네임을 제출하면") {
            then("INVALID_NICKNAME 으로 거절된다") {
                val repo = FakeMemberRepository().apply { seed(member(1L)) }
                val useCase = profileUseCase(repo)

                val e = shouldThrow<OnboardingException> {
                    useCase.completeOnboarding(input(nickname = "   "))
                }

                e.errorCode shouldBe OnboardingErrorCode.INVALID_NICKNAME
            }
        }
    }

    given("입력 정규화") {
        `when`("닉네임 앞뒤에 공백이 있으면") {
            then("공백을 제거해 저장한다") {
                val repo = FakeMemberRepository().apply { seed(member(1L)) }
                val useCase = profileUseCase(repo)

                useCase.completeOnboarding(input(nickname = "  길동이  "))

                repo.findById(1L)!!.profile.nickname shouldBe "길동이"
            }
        }

        `when`("같은 성분 코드가 중복 포함되면") {
            then("중복이 제거된 집합으로 저장된다") {
                val repo = FakeMemberRepository().apply { seed(member(1L)) }
                val useCase = profileUseCase(repo)

                useCase.completeOnboarding(input(avoidanceSubstanceCodes = listOf("EGG", "EGG", "MILK")))

                repo.findById(1L)!!.profile.avoidanceSubstanceCodes.map { it.value }.toSet() shouldBe setOf("EGG", "MILK")
            }
        }
    }

    given("무효 입력으로 거절된 뒤") {
        `when`("유효한 입력으로 다시 제출하면") {
            then("정상적으로 온보딩이 완료된다") {
                val repo = FakeMemberRepository().apply { seed(member(1L)) }
                val useCase = profileUseCase(repo)
                shouldThrow<OnboardingException> { useCase.completeOnboarding(input(countryCode = "ZZ")) }

                useCase.completeOnboarding(input())

                repo.findById(1L)!!.onboardingCompleted shouldBe true
            }
        }
    }

    given("프로필 조회의 랭킹 요약") {
        `when`("메뉴판을 40번 스캔한 회원이 조회하면") {
            then("프로필과 함께 등급·점수·다음 등급이 담긴다") {
                val repo = FakeMemberRepository().apply { seed(member(1L, onboardingCompleted = true, scanCount = 40)) }
                val useCase = profileUseCase(repo)

                val ranking = useCase.getMyProfile(1L).ranking

                ranking.tier shouldBe "explorer"
                ranking.level shouldBe 3
                ranking.score shouldBe 80
                ranking.nextTier shouldBe "regular"
                ranking.pointsToNext shouldBe 100
            }
        }

        `when`("활동이 없는 회원이 조회하면") {
            then("0점 최하 등급이 담긴다") {
                val repo = FakeMemberRepository().apply { seed(member(1L)) }
                val useCase = profileUseCase(repo)

                val ranking = useCase.getMyProfile(1L).ranking

                ranking.tier shouldBe "newcomer"
                ranking.score shouldBe 0
                ranking.pointsToNext shouldBe 30
            }
        }
    }

    given("내 프로필 조회") {
        `when`("온보딩을 완료한 회원이 조회하면") {
            then("저장된 프로필과 온보딩 완료 상태를 반환한다") {
                val profile = MemberProfile.of(
                    nickname = "길동이",
                    avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("EGG"), AvoidanceSubstanceCodeRef("MILK")),
                    spicinessPreference = 5,
                    countryCode = CountryCode.US,
                    appLanguage = LanguageCode.EN,
                )
                val repo = FakeMemberRepository().apply { seed(member(1L, onboardingCompleted = true, profile = profile)) }
                val useCase = profileUseCase(repo)

                val result = useCase.getMyProfile(1L)

                result.memberId shouldBe 1L
                result.nickname shouldBe "길동이"
                result.avoidanceSubstanceCodes.toSet() shouldBe setOf("EGG", "MILK")
                result.countryCode shouldBe "US"
                result.appLanguage shouldBe "en"
                result.onboardingCompleted shouldBe true
            }
        }

        `when`("온보딩 미완료 회원이 조회하면") {
            then("빈 프로필과 온보딩 미완료 상태를 반환한다") {
                val repo = FakeMemberRepository().apply { seed(member(1L)) }
                val useCase = profileUseCase(repo)

                val result = useCase.getMyProfile(1L)

                result.nickname shouldBe null
                result.avoidanceSubstanceCodes.shouldBeEmpty()
                result.countryCode shouldBe null
                result.appLanguage shouldBe null
                result.onboardingCompleted shouldBe false
            }
        }

        `when`("존재하지 않는 회원 식별자로 조회하면") {
            then("MEMBER_NOT_FOUND 로 거절된다") {
                val useCase = profileUseCase(FakeMemberRepository())

                val e = shouldThrow<MemberException> { useCase.getMyProfile(99L) }

                e.errorCode shouldBe MemberErrorCode.MEMBER_NOT_FOUND
            }
        }
    }

    given("온보딩을 완료한 회원의 프로필 수정") {
        val onboarded = MemberProfile.of(
            nickname = "원래닉",
            avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("EGG")),
            spicinessPreference = 5,
            countryCode = CountryCode.KR,
            appLanguage = LanguageCode.KO,
        )

        `when`("유효한 값으로 수정하면") {
            then("프로필이 새 값으로 바뀌고 온보딩 완료 상태는 유지된다") {
                val repo = FakeMemberRepository().apply { seed(member(1L, onboardingCompleted = true, profile = onboarded)) }
                val useCase = profileUseCase(repo)

                useCase.update(updateInput(nickname = "새닉", avoidanceSubstanceCodes = listOf("MILK"), countryCode = "US", appLanguage = "en"))

                val saved = repo.findById(1L)!!
                saved.profile.nickname shouldBe "새닉"
                saved.profile.avoidanceSubstanceCodes.map { it.value }.toSet() shouldBe setOf("MILK")
                saved.profile.countryCode shouldBe CountryCode.US
                saved.profile.appLanguage shouldBe LanguageCode.EN
                saved.onboardingCompleted shouldBe true
            }
        }

        `when`("무효한 성분 코드로 수정하면") {
            then("INVALID_AVOIDANCE_SUBSTANCE_CODE 로 거절되고 프로필은 변하지 않는다") {
                val repo = FakeMemberRepository().apply { seed(member(1L, onboardingCompleted = true, profile = onboarded)) }
                val useCase = profileUseCase(repo)

                val e = shouldThrow<OnboardingException> {
                    useCase.update(updateInput(avoidanceSubstanceCodes = listOf("NOT_A_CODE")))
                }

                e.errorCode shouldBe OnboardingErrorCode.INVALID_AVOIDANCE_SUBSTANCE_CODE
                repo.findById(1L)!!.profile.nickname shouldBe "원래닉"
            }
        }

        `when`("존재하지 않는 회원의 프로필을 수정하면") {
            then("MEMBER_NOT_FOUND 로 거절된다") {
                val useCase = profileUseCase(FakeMemberRepository())

                val e = shouldThrow<MemberException> { useCase.update(updateInput(memberId = 99L, nickname = "새닉")) }

                e.errorCode shouldBe MemberErrorCode.MEMBER_NOT_FOUND
            }
        }

        `when`("온보딩 미완료 회원의 프로필을 수정하면") {
            then("온보딩 완료로 전이되지 않고 미완료 상태가 유지된다") {
                val repo = FakeMemberRepository().apply { seed(member(1L)) }
                val useCase = profileUseCase(repo)

                useCase.update(updateInput(nickname = "새닉"))

                repo.findById(1L)!!.onboardingCompleted shouldBe false
            }
        }
    }

    given("프로필 부분 수정 — 미전송 필드는 기존 값을 유지한다") {
        val onboarded = MemberProfile.of(
            nickname = "원래닉",
            avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("EGG"), AvoidanceSubstanceCodeRef("MILK")),
            spicinessPreference = 8,
            countryCode = CountryCode.KR,
            appLanguage = LanguageCode.KO,
        )

        fun seeded(): FakeMemberRepository =
            FakeMemberRepository().apply { seed(member(1L, onboardingCompleted = true, profile = onboarded)) }

        `when`("닉네임·국가·언어만 보내면") {
            then("세 값만 바뀌고 기피 성분은 그대로 유지된다") {
                val repo = seeded()

                MemberProfileUseCase(repo).update(
                    updateInput(nickname = "새닉", countryCode = "US", appLanguage = "en"),
                )

                val saved = repo.findById(1L)!!.profile
                saved.nickname shouldBe "새닉"
                saved.countryCode shouldBe CountryCode.US
                saved.appLanguage shouldBe LanguageCode.EN
                saved.avoidanceSubstanceCodes.map { it.value }.toSet() shouldBe setOf("EGG", "MILK")
            }
        }

        `when`("닉네임만 보내면") {
            then("닉네임만 바뀌고 국가·언어·기피 성분은 모두 유지된다") {
                val repo = seeded()

                MemberProfileUseCase(repo).update(updateInput(nickname = "새닉"))

                val saved = repo.findById(1L)!!.profile
                saved.nickname shouldBe "새닉"
                saved.countryCode shouldBe CountryCode.KR
                saved.appLanguage shouldBe LanguageCode.KO
                saved.avoidanceSubstanceCodes.map { it.value }.toSet() shouldBe setOf("EGG", "MILK")
            }
        }

        `when`("아무 필드도 보내지 않으면") {
            then("프로필이 하나도 바뀌지 않는다") {
                val repo = seeded()

                MemberProfileUseCase(repo).update(updateInput())

                val saved = repo.findById(1L)!!.profile
                saved.nickname shouldBe "원래닉"
                saved.countryCode shouldBe CountryCode.KR
                saved.appLanguage shouldBe LanguageCode.KO
                saved.avoidanceSubstanceCodes.map { it.value }.toSet() shouldBe setOf("EGG", "MILK")
            }
        }

        `when`("일부 필드만 수정하면") {
            then("API 로 노출되지 않는 맵기 선호도는 보존된다") {
                val repo = seeded()

                MemberProfileUseCase(repo).update(updateInput(nickname = "새닉"))

                repo.findById(1L)!!.profile.spicinessPreference shouldBe 8
            }
        }
    }

    given("프로필 부분 수정 — 기피 성분의 빈 목록과 미전송은 다르다") {
        val onboarded = MemberProfile.of(
            nickname = "원래닉",
            avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("EGG"), AvoidanceSubstanceCodeRef("MILK")),
            spicinessPreference = 5,
            countryCode = CountryCode.KR,
            appLanguage = LanguageCode.KO,
        )

        fun seeded(): FakeMemberRepository =
            FakeMemberRepository().apply { seed(member(1L, onboardingCompleted = true, profile = onboarded)) }

        `when`("기피 성분만 보내면") {
            then("기피 성분만 교체되고 닉네임·국가·언어는 유지된다") {
                val repo = seeded()

                MemberProfileUseCase(repo).update(updateInput(avoidanceSubstanceCodes = listOf("PEANUT")))

                val saved = repo.findById(1L)!!.profile
                saved.avoidanceSubstanceCodes.map { it.value }.toSet() shouldBe setOf("PEANUT")
                saved.nickname shouldBe "원래닉"
                saved.countryCode shouldBe CountryCode.KR
                saved.appLanguage shouldBe LanguageCode.KO
            }
        }

        `when`("기피 성분에 빈 목록을 보내면") {
            then("기피 성분이 전부 해제된다") {
                val repo = seeded()

                MemberProfileUseCase(repo).update(updateInput(avoidanceSubstanceCodes = emptyList()))

                repo.findById(1L)!!.profile.avoidanceSubstanceCodes.shouldBeEmpty()
            }
        }

        `when`("기피 성분을 보내지 않으면") {
            then("기존 기피 성분이 유지된다") {
                val repo = seeded()

                MemberProfileUseCase(repo).update(updateInput(nickname = "새닉"))

                repo.findById(1L)!!.profile.avoidanceSubstanceCodes.map { it.value }.toSet() shouldBe setOf("EGG", "MILK")
            }
        }
    }

    given("프로필 부분 수정 — 검증은 전달된 필드에만 적용한다") {
        val onboarded = MemberProfile.of(
            nickname = "원래닉",
            avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("EGG")),
            spicinessPreference = 5,
            countryCode = CountryCode.KR,
            appLanguage = LanguageCode.KO,
        )

        fun seeded(): FakeMemberRepository =
            FakeMemberRepository().apply { seed(member(1L, onboardingCompleted = true, profile = onboarded)) }

        `when`("잘못된 국가 코드만 보내면") {
            then("INVALID_COUNTRY_CODE 로 거절되고 프로필은 하나도 바뀌지 않는다") {
                val repo = seeded()

                val e = shouldThrow<OnboardingException> {
                    MemberProfileUseCase(repo).update(updateInput(countryCode = "ZZ"))
                }

                e.errorCode shouldBe OnboardingErrorCode.INVALID_COUNTRY_CODE
                val kept = repo.findById(1L)!!.profile
                kept.nickname shouldBe "원래닉"
                kept.countryCode shouldBe CountryCode.KR
            }
        }

        `when`("공백뿐인 닉네임을 보내면") {
            then("INVALID_NICKNAME 으로 거절된다") {
                val repo = seeded()

                val e = shouldThrow<OnboardingException> {
                    MemberProfileUseCase(repo).update(updateInput(nickname = "   "))
                }

                e.errorCode shouldBe OnboardingErrorCode.INVALID_NICKNAME
                repo.findById(1L)!!.profile.nickname shouldBe "원래닉"
            }
        }

        `when`("유효한 닉네임만 보내면") {
            then("국가·언어를 보내지 않았다는 이유로 거절되지 않는다") {
                val repo = seeded()

                MemberProfileUseCase(repo).update(updateInput(nickname = "  새닉  "))

                repo.findById(1L)!!.profile.nickname shouldBe "새닉"
            }
        }
    }
})

