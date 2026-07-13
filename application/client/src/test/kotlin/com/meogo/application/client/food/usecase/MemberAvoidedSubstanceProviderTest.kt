package com.meogo.application.client.food.usecase

import com.meogo.domain.avoidance.AvoidanceSubstanceCode
import com.meogo.domain.member.AvoidanceSubstanceCodeRef
import com.meogo.domain.member.Member
import com.meogo.domain.member.MemberProfile
import com.meogo.domain.member.MemberRepository
import com.meogo.domain.member.SocialIdentity
import com.meogo.domain.member.SocialProvider
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

private class FakeMemberRepository(
    private val members: Map<Long, Member>,
) : MemberRepository {
    override fun findById(id: Long): Member? = members[id]

    override fun findByIdentity(provider: SocialProvider, providerUserId: String): Member? = null

    override fun saveNew(member: Member): Member = member

    override fun update(member: Member): Member = member

    override fun withdraw(id: Long) = Unit

    override fun increaseScanCount(memberId: Long) = Unit
}

class MemberAvoidedSubstanceProviderTest : BehaviorSpec({
    fun member(id: Long, codes: Set<String>): Member =
        Member.reconstitute(
            id = id,
            identity = SocialIdentity(SocialProvider.GOOGLE, "uid-$id", null),
            profile = MemberProfile.of(
                nickname = "닉네임$id",
                avoidanceSubstanceCodes = codes.map { AvoidanceSubstanceCodeRef(it) }.toSet(),
                spicinessPreference = MemberProfile.DEFAULT_SPICINESS_PREFERENCE,
                countryCode = null,
                appLanguage = null,
            ),
            onboardingCompleted = true,
        )

    fun provider(vararg members: Member) =
        MemberAvoidedSubstanceProvider(FakeMemberRepository(members.associateBy { it.id!! }))

    given("회원 프로필 기반 기피 성분 제공자") {
        `when`("기피 성분을 설정한 회원의 memberId 로 조회하면") {
            then("프로필에 저장된 코드 집합을 반환한다") {
                val p = provider(member(11L, setOf("EGG", "MILK")))

                p.avoidedCodes(11L) shouldBe setOf(AvoidanceSubstanceCode.EGG, AvoidanceSubstanceCode.MILK)
            }
        }

        `when`("기피 성분을 설정하지 않은 회원이면") {
            then("빈 집합을 반환한다") {
                val p = provider(member(11L, emptySet()))

                p.avoidedCodes(11L) shouldBe emptySet<AvoidanceSubstanceCode>()
            }
        }

        `when`("memberId 가 null(비회원)이면") {
            then("빈 집합을 반환한다") {
                val p = provider(member(11L, setOf("EGG")))

                p.avoidedCodes(null) shouldBe emptySet<AvoidanceSubstanceCode>()
            }
        }

        `when`("존재하지 않는 회원이면") {
            then("빈 집합을 반환한다") {
                val p = provider()

                p.avoidedCodes(99L) shouldBe emptySet<AvoidanceSubstanceCode>()
            }
        }
    }
})
