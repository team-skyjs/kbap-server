package com.meogo.application.client.member

import com.meogo.application.client.auth.AuthErrorCode
import com.meogo.application.client.auth.AuthException
import com.meogo.application.client.auth.SocialAccountDeleter
import com.meogo.core.member.Member
import com.meogo.core.member.MemberErrorCode
import com.meogo.core.member.MemberException
import com.meogo.core.member.MemberProfile
import com.meogo.core.member.MemberRepository
import com.meogo.core.member.SocialIdentity
import com.meogo.core.member.SocialProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class WithdrawUseCaseTest : BehaviorSpec({

    val identity = SocialIdentity(SocialProvider.GOOGLE, "google-sub-1", "user@gmail.com")
    val member = Member.reconstitute(
        id = 7L,
        identity = identity,
        profile = MemberProfile.empty(),
        onboardingCompleted = true,
    )

    fun useCase(repository: MemberRepository, deleter: SocialAccountDeleter) =
        WithdrawUseCase(memberRepository = repository, socialAccountDeleter = deleter)

    given("활성 회원의 탈퇴 요청") {
        `when`("탈퇴하면") {
            then("회원의 소셜 신원으로 인증 제공자 계정을 먼저 지우고 그 다음 회원을 소프트 삭제한다") {
                val callLog = mutableListOf<String>()
                val repository = FakeWithdrawMemberRepository(member, callLog)
                val deleter = FakeAccountDeleter(callLog)

                useCase(repository, deleter).withdraw(7L)

                deleter.deleted shouldBe listOf(SocialProvider.GOOGLE to "google-sub-1")
                repository.withdrawnIds shouldBe listOf(7L)
                callLog shouldBe listOf("delete:GOOGLE:google-sub-1", "withdraw:7")
            }
        }
    }

    given("존재하지 않거나 이미 탈퇴한 회원") {
        `when`("탈퇴하면") {
            then("MEMBER_NOT_FOUND 로 거절되고 인증 제공자 계정도 지우지 않는다") {
                val repository = FakeWithdrawMemberRepository(null, mutableListOf())
                val deleter = FakeAccountDeleter(mutableListOf())

                val e = shouldThrow<MemberException> { useCase(repository, deleter).withdraw(7L) }

                e.errorCode shouldBe MemberErrorCode.MEMBER_NOT_FOUND
                deleter.deleted.shouldBeEmpty()
                repository.withdrawnIds.shouldBeEmpty()
            }
        }
    }

    given("인증 제공자 계정 삭제가 실패하는 상황") {
        `when`("탈퇴하면") {
            then("SOCIAL_ACCOUNT_DELETE_FAILED 로 거절되고 회원 행은 그대로 남는다") {
                val repository = FakeWithdrawMemberRepository(member, mutableListOf())
                val deleter = FailingAccountDeleter()

                val e = shouldThrow<AuthException> { useCase(repository, deleter).withdraw(7L) }

                e.errorCode shouldBe AuthErrorCode.SOCIAL_ACCOUNT_DELETE_FAILED
                repository.withdrawnIds.shouldBeEmpty()
            }
        }
    }
})

private class FakeAccountDeleter(private val callLog: MutableList<String>) : SocialAccountDeleter {
    val deleted: MutableList<Pair<SocialProvider, String>> = mutableListOf()

    override fun delete(provider: SocialProvider, providerUserId: String) {
        deleted += provider to providerUserId
        callLog += "delete:$provider:$providerUserId"
    }
}

private class FailingAccountDeleter : SocialAccountDeleter {
    override fun delete(provider: SocialProvider, providerUserId: String): Unit =
        throw IllegalStateException("인증 제공자 계정 삭제 실패")
}

private class FakeWithdrawMemberRepository(
    private val member: Member?,
    private val callLog: MutableList<String>,
) : MemberRepository {
    val withdrawnIds: MutableList<Long> = mutableListOf()

    override fun findById(id: Long): Member? = member

    override fun findByIdentity(provider: SocialProvider, providerUserId: String): Member? = null

    override fun saveNew(member: Member): Member = member

    override fun update(member: Member): Member = member

    override fun withdraw(id: Long) {
        withdrawnIds += id
        callLog += "withdraw:$id"
    }
}
