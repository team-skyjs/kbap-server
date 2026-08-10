package com.kbap.common.domain.member.model

import com.kbap.common.util.ImageUrls
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch

private const val NICKNAME_COLUMN_MAX_LENGTH = 30
private const val PROFILE_IMAGE_PATH_MAX_LENGTH = 512
private const val DRAWS = 1_000

class OnboardingProfileDefaultsTest : BehaviorSpec({

    given("온보딩 자동 지정 닉네임") {
        `when`("닉네임을 생성하면") {
            then("영숫자 6자 코드 형식이고 저장 가능한 길이 안에 있다") {
                repeat(DRAWS) {
                    val nickname = OnboardingProfileDefaults.randomNickname()

                    nickname shouldMatch Regex("^[A-HJ-NP-Z2-9]{6}$")
                    nickname.length shouldBe 6
                    nickname.trim() shouldBe nickname
                    (nickname.length <= NICKNAME_COLUMN_MAX_LENGTH) shouldBe true
                }
            }
        }

        `when`("혼동하기 쉬운 문자를 확인하면") {
            then("0·O·1·I 가 한 번도 나오지 않는다") {
                val generated = (1..DRAWS).map { OnboardingProfileDefaults.randomNickname() }

                generated.none { it.any { ch -> ch in "0O1I" } } shouldBe true
            }
        }

        `when`("연속으로 여러 번 생성하면") {
            then("거의 매번 다른 값이 나온다") {
                val generated = (1..DRAWS).map { OnboardingProfileDefaults.randomNickname() }

                generated.distinct().size shouldBeGreaterThan (DRAWS * 95 / 100)
            }
        }
    }

    given("온보딩 자동 지정 프로필 이미지 후보") {
        `when`("후보 목록을 확인하면") {
            then("비어 있지 않고 모든 경로가 저장 규칙을 만족한다") {
                OnboardingProfileDefaults.PROFILE_IMAGE_PATHS.isNotEmpty() shouldBe true

                OnboardingProfileDefaults.PROFILE_IMAGE_PATHS.forEach { path ->
                    ImageUrls.isAbsoluteUrl(path) shouldBe false
                    path.startsWith("/") shouldBe false
                    path.isNotBlank() shouldBe true
                    (path.length <= PROFILE_IMAGE_PATH_MAX_LENGTH) shouldBe true
                }
            }
        }

        `when`("연속으로 여러 번 추첨하면") {
            then("항상 후보 안의 값이 나오고 한 값에 쏠리지 않는다") {
                val drawn = (1..DRAWS).map { OnboardingProfileDefaults.randomProfileImagePath() }

                drawn.forEach { OnboardingProfileDefaults.PROFILE_IMAGE_PATHS shouldContain it }
                drawn.groupingBy { it }.eachCount().values.max() shouldBeLessThan (DRAWS * 30 / 100)
            }
        }
    }

    given("자동 지정 값과 프로필 저장 검증 규칙") {
        `when`("생성 닉네임과 모든 이미지 후보를 프로필에 반영하면") {
            then("검증 예외 없이 그대로 저장된다") {
                OnboardingProfileDefaults.PROFILE_IMAGE_PATHS.forEach { path ->
                    val profile = MemberProfile.empty().updatedWith(
                        nickname = OnboardingProfileDefaults.randomNickname(),
                        profileImageUrl = path,
                    )

                    profile.nickname!! shouldMatch Regex("^[A-HJ-NP-Z2-9]{6}$")
                    profile.profileImageUrl shouldBe path
                }
            }
        }
    }
})
