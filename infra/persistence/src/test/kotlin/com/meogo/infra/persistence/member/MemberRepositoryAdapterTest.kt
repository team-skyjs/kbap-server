package com.meogo.infra.persistence.member

import com.meogo.core.kernel.lang.CountryCode
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.member.AvoidanceSubstanceCodeRef
import com.meogo.core.member.Member
import com.meogo.core.member.MemberErrorCode
import com.meogo.core.member.MemberException
import com.meogo.core.member.MemberProfile
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
    private lateinit var memberJpaRepository: MemberJpaRepository

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun clear() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM member")
                }
            }
        }

        fun softDeleteMember(id: Long) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("UPDATE member SET status = 'DELETED' WHERE id = ?").use {
                    it.setLong(1, id)
                    it.executeUpdate()
                }
            }
        }

        fun suspendMember(id: Long) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("UPDATE member SET member_status = 'SUSPENDED' WHERE id = ?").use {
                    it.setLong(1, id)
                    it.executeUpdate()
                }
            }
        }

        fun readColumn(id: Long, column: String): String? =
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT $column FROM member WHERE id = ?").use { statement ->
                    statement.setLong(1, id)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.getString(1) else null
                    }
                }
            }

        fun googleIdentity(sub: String = "google-sub-1", email: String? = "user@gmail.com") =
            SocialIdentity(SocialProvider.GOOGLE, sub, email)

        fun newMember(identity: SocialIdentity) = Member.signUp(identity)

        beforeContainer { clear() }

        given("saveNew — 회원·신원 저장") {
            `when`("신규 회원을 저장하면") {
                then("member 한 행에 신원이 저장되고 findByIdentity 로 복원된다") {
                    val saved = adapter.saveNew(newMember(googleIdentity()))

                    saved.id.shouldNotBeNull()
                    val found = adapter.findByIdentity(SocialProvider.GOOGLE, "google-sub-1")
                    found.shouldNotBeNull()
                    found.identity.providerUserId shouldBe "google-sub-1"
                    found.identity.email shouldBe "user@gmail.com"
                    found.onboardingCompleted shouldBe false
                }
            }

            `when`("빈 프로필로 가입하면") {
                then("맵기 선호 기본값 5 와 빈 기피성분이 복원된다") {
                    val saved = adapter.saveNew(newMember(googleIdentity(sub = "empty-profile-sub")))

                    val found = adapter.findById(saved.id!!)
                    found.shouldNotBeNull()
                    found.profile.spicinessPreference shouldBe 5
                    found.profile.avoidanceSubstanceCodes shouldBe emptySet()
                    found.profile.countryCode.shouldBeNull()
                    found.profile.appLanguage.shouldBeNull()
                }
            }
        }

        given("프로필 값이 채워진 회원 저장") {
            `when`("기피성분·맵기·국가·언어·닉네임을 담아 저장하면") {
                then("findById 재조회 시 profile JSON 값이 그대로 복원된다") {
                    val profile = MemberProfile.of(
                        nickname = "머고",
                        avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("PEANUT"), AvoidanceSubstanceCodeRef("SOYBEAN")),
                        spicinessPreference = 7,
                        countryCode = CountryCode.KR,
                        appLanguage = LanguageCode.EN,
                    )
                    val toSave = Member.reconstitute(
                        id = 0L,
                        identity = googleIdentity(sub = "sub-profile"),
                        profile = profile,
                        onboardingCompleted = true,
                    )
                    val saved = adapter.saveNew(toSave)

                    val found = adapter.findById(saved.id!!)
                    found.shouldNotBeNull()
                    found.profile.nickname shouldBe "머고"
                    found.profile.avoidanceSubstanceCodes shouldBe profile.avoidanceSubstanceCodes
                    found.profile.spicinessPreference shouldBe 7
                    found.profile.countryCode shouldBe CountryCode.KR
                    found.profile.appLanguage shouldBe LanguageCode.EN
                    found.onboardingCompleted shouldBe true
                }
            }

            `when`("온보딩을 완료한 회원을 저장하면") {
                then("onboarding_completed 컬럼에 boolean true 로 저장된다") {
                    val toSave = Member.reconstitute(
                        id = 0L,
                        identity = googleIdentity(sub = "onboarding-bool-sub"),
                        profile = MemberProfile.empty(),
                        onboardingCompleted = true,
                    )
                    val saved = adapter.saveNew(toSave)

                    readColumn(saved.id!!, "onboarding_completed") shouldBe "1"
                }
            }
        }

        given("동일 (provider, providerUid) 중복 저장") {
            `when`("같은 소셜 계정을 다시 saveNew 하면") {
                then("유니크 제약이 막아 DUPLICATE_SOCIAL_IDENTITY 예외를 던진다") {
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
                    val filled = MemberProfile.of(
                        nickname = "머고",
                        avoidanceSubstanceCodes = setOf(AvoidanceSubstanceCodeRef("PEANUT")),
                        spicinessPreference = 4,
                        countryCode = CountryCode.JP,
                        appLanguage = LanguageCode.JA,
                    )

                    adapter.update(saved.updateProfile(filled).completeOnboarding())

                    val found = adapter.findById(saved.id!!)
                    found.shouldNotBeNull()
                    found.profile.nickname shouldBe "머고"
                    found.profile.avoidanceSubstanceCodes shouldBe filled.avoidanceSubstanceCodes
                    found.profile.spicinessPreference shouldBe 4
                    found.profile.countryCode shouldBe CountryCode.JP
                    found.profile.appLanguage shouldBe LanguageCode.JA
                    found.onboardingCompleted shouldBe true
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

        given("회원 탈퇴 — 소프트 삭제 + 신원 더미 치환") {
            `when`("활성 회원을 탈퇴 처리하면") {
                then("조회에서 제외되고 같은 소셜 계정 재가입이 유니크 충돌 없이 성공한다") {
                    val saved = adapter.saveNew(newMember(googleIdentity(sub = "withdraw-sub")))

                    adapter.withdraw(saved.id!!)

                    adapter.findById(saved.id!!).shouldBeNull()
                    adapter.findByIdentity(SocialProvider.GOOGLE, "withdraw-sub").shouldBeNull()

                    val resignup = adapter.saveNew(newMember(googleIdentity(sub = "withdraw-sub")))
                    resignup.id.shouldNotBeNull()
                    (resignup.id != saved.id) shouldBe true
                }
            }

            `when`("탈퇴한 회원 행을 직접 조회하면") {
                then("소셜 식별자는 삭제 표식으로 대체되고 이메일은 그대로 남는다") {
                    val saved = adapter.saveNew(
                        newMember(googleIdentity(sub = "marker-sub", email = "kept@gmail.com")),
                    )
                    val id = saved.id!!

                    adapter.withdraw(id)

                    readColumn(id, "provider_uid") shouldBe "DELETED:$id"
                    readColumn(id, "email") shouldBe "kept@gmail.com"
                }
            }

            `when`("같은 소셜 계정으로 가입·탈퇴를 반복하면") {
                then("삭제 표식이 회원마다 유일해 탈퇴 행끼리도 충돌하지 않는다") {
                    val first = adapter.saveNew(newMember(googleIdentity(sub = "repeat-sub")))
                    adapter.withdraw(first.id!!)

                    val second = adapter.saveNew(newMember(googleIdentity(sub = "repeat-sub")))
                    adapter.withdraw(second.id!!)

                    val third = adapter.saveNew(newMember(googleIdentity(sub = "repeat-sub")))
                    third.id.shouldNotBeNull()
                    readColumn(first.id!!, "provider_uid") shouldBe "DELETED:${first.id}"
                    readColumn(second.id!!, "provider_uid") shouldBe "DELETED:${second.id}"
                }
            }

            `when`("존재하지 않는 회원을 탈퇴 처리하면") {
                then("MEMBER_NOT_FOUND 예외를 던진다") {
                    val e = shouldThrow<MemberException> { adapter.withdraw(999_999L) }
                    e.errorCode shouldBe MemberErrorCode.MEMBER_NOT_FOUND
                }
            }
        }

        given("회원 정지 상태 — 소프트 삭제와 별개 축") {
            `when`("신규 회원을 저장하면") {
                then("member_status 는 기본 ACTIVE 다") {
                    val saved = adapter.saveNew(newMember(googleIdentity(sub = "status-default-sub")))

                    readColumn(saved.id!!, "member_status") shouldBe MemberStatus.ACTIVE.name
                }
            }

            `when`("정지된 회원을 서비스 조회 경로로 조회하면") {
                then("findById·findByIdentity 에서 제외된다") {
                    val saved = adapter.saveNew(newMember(googleIdentity(sub = "suspended-sub")))
                    suspendMember(saved.id!!)

                    adapter.findById(saved.id!!).shouldBeNull()
                    adapter.findByIdentity(SocialProvider.GOOGLE, "suspended-sub").shouldBeNull()
                }
            }

            `when`("정지된 회원을 관리자 조회 경로로 조회하면") {
                then("정지 회원이 그대로 보이고 소프트 삭제 상태는 ACTIVE 로 남는다") {
                    val saved = adapter.saveNew(newMember(googleIdentity(sub = "admin-view-sub")))
                    suspendMember(saved.id!!)

                    val entity = memberJpaRepository.findById(saved.id!!).orElse(null)
                    entity.shouldNotBeNull()
                    entity.memberStatus shouldBe MemberStatus.SUSPENDED
                    entity.isActive() shouldBe true
                    readColumn(saved.id!!, "status") shouldBe "ACTIVE"
                }
            }
        }

        given("스캔 횟수 — 랭킹 카운트") {
            `when`("가입 직후 회원을 저장하면") {
                then("스캔·리뷰·고유 음식 카운트가 모두 0으로 초기화된다") {
                    val saved = adapter.saveNew(Member.signUp(googleIdentity()))

                    saved.scanCount shouldBe 0
                    saved.reviewCount shouldBe 0
                    saved.uniqueReviewedFoodCount shouldBe 0
                    readColumn(saved.id!!, "scan_count") shouldBe "0"
                    readColumn(saved.id!!, "review_count") shouldBe "0"
                    readColumn(saved.id!!, "unique_reviewed_food_count") shouldBe "0"
                }
            }

            `when`("리뷰 카운트가 쌓인 회원을 조회하면") {
                then("컬럼 값이 도메인으로 복원된다") {
                    val saved = adapter.saveNew(Member.signUp(googleIdentity()))
                    dataSource.connection.use { c ->
                        c.createStatement().use {
                            it.execute(
                                "UPDATE member SET review_count = 8, unique_reviewed_food_count = 6, scan_count = 9 " +
                                    "WHERE id = ${saved.id}",
                            )
                        }
                    }

                    val ranking = adapter.findById(saved.id!!)!!.ranking()

                    ranking.score shouldBe 128
                }
            }

            `when`("스캔을 기록해 저장하면") {
                then("올라간 횟수가 영속되고 다시 조회해도 유지된다") {
                    val saved = adapter.saveNew(Member.signUp(googleIdentity()))

                    adapter.update(saved.recordScan().recordScan())

                    adapter.findById(saved.id!!)!!.scanCount shouldBe 2
                    readColumn(saved.id!!, "scan_count") shouldBe "2"
                }
            }

            `when`("스캔 이후 프로필을 갱신하면") {
                then("스캔 횟수가 보존된다") {
                    val saved = adapter.saveNew(Member.signUp(googleIdentity()))
                    val scanned = adapter.update(saved.recordScan())

                    adapter.update(scanned.updateProfile(MemberProfile.empty()))

                    adapter.findById(saved.id!!)!!.scanCount shouldBe 1
                }
            }
        }
    }
}
