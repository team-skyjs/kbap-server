package com.meogo.infra.persistence.member

import com.meogo.core.member.AvoidanceSubstanceCodeRef
import com.meogo.core.member.Member
import com.meogo.core.member.MemberErrorCode
import com.meogo.core.member.MemberException
import com.meogo.core.member.MemberProfile
import com.meogo.core.member.OnboardingStatus
import com.meogo.core.member.SocialIdentity
import com.meogo.core.member.SocialProvider
import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import javax.sql.DataSource

@SpringBootTest
@Import(MySqlContainerConfig::class)
class MemberRepositoryAdapterTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var adapter: MemberRepositoryAdapter

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun clear() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM member_social_identities")
                    statement.execute("DELETE FROM members")
                }
            }
        }

        fun softDeleteMember(id: Long) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("UPDATE members SET status = 'DELETED' WHERE id = ?").use {
                    it.setLong(1, id)
                    it.executeUpdate()
                }
            }
        }

        fun googleIdentity(sub: String = "google-sub-1", email: String? = "user@gmail.com") =
            SocialIdentity(SocialProvider.GOOGLE, sub, email)

        fun newMember(identity: SocialIdentity) = Member.signUp(identity)

        beforeContainer { clear() }

        given("saveNew — 회원·신원 저장") {
            `when`("신규 회원을 저장하면") {
                then("members·member_social_identities 에 저장되고 findByIdentity 로 복원된다") {
                    val saved = adapter.saveNew(newMember(googleIdentity()))

                    saved.id.shouldNotBeNull()
                    val found = adapter.findByIdentity(SocialProvider.GOOGLE, "google-sub-1")
                    found.shouldNotBeNull()
                    found.identities.first().providerUserId shouldBe "google-sub-1"
                    found.identities.first().email shouldBe "user@gmail.com"
                    found.onboardingStatus shouldBe OnboardingStatus.PENDING
                }
            }
        }

        given("프로필 값이 채워진 회원 저장") {
            `when`("기피성분·맵기·국가·언어·닉네임을 담아 저장하면") {
                then("findById 재조회 시 값이 그대로 복원된다") {
                    val profile = MemberProfile(
                        nickname = "머고",
                        avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("PEANUT"), AvoidanceSubstanceCodeRef("SOYBEAN")),
                        spicinessPreference = 7,
                        countryCode = "KR",
                        appLanguage = com.meogo.core.kernel.lang.LanguageCode.EN,
                    )
                    val toSave = Member.reconstitute(
                        id = 0L,
                        identities = listOf(googleIdentity(sub = "sub-profile")),
                        profile = profile,
                        onboardingStatus = OnboardingStatus.COMPLETED,
                    )
                    val saved = adapter.saveNew(toSave)

                    val found = adapter.findById(saved.id!!)
                    found.shouldNotBeNull()
                    found.profile.nickname shouldBe "머고"
                    found.profile.avoidanceSubstanceCodes shouldBe profile.avoidanceSubstanceCodes
                    found.profile.spicinessPreference shouldBe 7
                    found.profile.countryCode shouldBe "KR"
                    found.profile.appLanguage shouldBe com.meogo.core.kernel.lang.LanguageCode.EN
                    found.onboardingStatus shouldBe OnboardingStatus.COMPLETED
                }
            }
        }

        given("동일 (provider, providerUserId) 중복 저장") {
            `when`("같은 소셜 계정을 다시 saveNew 하면") {
                then("DUPLICATE_SOCIAL_IDENTITY 예외를 던진다") {
                    adapter.saveNew(newMember(googleIdentity(sub = "dup-sub")))

                    val e = shouldThrow<MemberException> {
                        adapter.saveNew(newMember(googleIdentity(sub = "dup-sub", email = "other@gmail.com")))
                    }
                    e.errorCode shouldBe MemberErrorCode.DUPLICATE_SOCIAL_IDENTITY
                }
            }
        }

        given("update — 프로필·온보딩 상태 갱신") {
            `when`("빈 프로필 회원의 프로필을 채우고 온보딩을 완료해 update 하면") {
                then("findById 재조회 시 갱신 값이 그대로 반환된다") {
                    val saved = adapter.saveNew(newMember(googleIdentity(sub = "update-sub")))
                    val filled = MemberProfile(
                        nickname = "머고",
                        avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("PEANUT")),
                        spicinessPreference = 4,
                        countryCode = "JP",
                        appLanguage = com.meogo.core.kernel.lang.LanguageCode.JA,
                    )

                    adapter.update(saved.updateProfile(filled).completeOnboarding())

                    val found = adapter.findById(saved.id!!)
                    found.shouldNotBeNull()
                    found.profile.nickname shouldBe "머고"
                    found.profile.avoidanceSubstanceCodes shouldBe filled.avoidanceSubstanceCodes
                    found.profile.spicinessPreference shouldBe 4
                    found.profile.countryCode shouldBe "JP"
                    found.profile.appLanguage shouldBe com.meogo.core.kernel.lang.LanguageCode.JA
                    found.onboardingStatus shouldBe OnboardingStatus.COMPLETED
                }
            }
        }

        given("소프트삭제된 회원") {
            `when`("findById·findByIdentity 로 조회하면") {
                then("탈퇴 회원은 반환되지 않는다") {
                    val saved = adapter.saveNew(newMember(googleIdentity(sub = "deleted-sub")))
                    softDeleteMember(saved.id!!)

                    adapter.findById(saved.id!!).shouldBeNull()
                    adapter.findByIdentity(SocialProvider.GOOGLE, "deleted-sub").shouldBeNull()
                }
            }
        }

        given("회원 탈퇴 — 회원 soft delete + 신원 hard delete") {
            `when`("활성 회원을 탈퇴 처리하면") {
                then("조회·신원 해소에서 제외되고, 같은 소셜 계정 재가입이 유니크 충돌 없이 성공한다") {
                    val saved = adapter.saveNew(newMember(googleIdentity(sub = "withdraw-sub")))

                    adapter.withdraw(saved.id!!)

                    adapter.findById(saved.id!!).shouldBeNull()
                    adapter.findByIdentity(SocialProvider.GOOGLE, "withdraw-sub").shouldBeNull()

                    val resignup = adapter.saveNew(newMember(googleIdentity(sub = "withdraw-sub")))
                    resignup.id.shouldNotBeNull()
                    (resignup.id != saved.id) shouldBe true
                }
            }

            `when`("존재하지 않는 회원을 탈퇴 처리하면") {
                then("MEMBER_NOT_FOUND 예외를 던진다") {
                    val e = shouldThrow<MemberException> { adapter.withdraw(999_999L) }
                    e.errorCode shouldBe MemberErrorCode.MEMBER_NOT_FOUND
                }
            }
        }
    }
}
