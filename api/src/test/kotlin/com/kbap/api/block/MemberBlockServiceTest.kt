package com.kbap.api.block

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.block.MemberBlockJpaRepository
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.api.member.MemberService
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.domain.member.model.SocialProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class MemberBlockServiceTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var memberBlockService: MemberBlockService

    @Autowired
    private lateinit var memberBlockRepository: MemberBlockJpaRepository

    @Autowired
    private lateinit var memberService: MemberService

    @Autowired
    private lateinit var memberRepository: MemberJpaRepository

    init {
        fun newMember(): Member =
            memberRepository.save(
                Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "block-service-${System.nanoTime()}", null)),
            )

        given("차단 등록") {
            `when`("다른 활성 회원을 차단하면") {
                then("차단 목록에 그 회원 id 가 들어간다") {
                    val blocker = newMember()
                    val target = newMember()
                    memberBlockService.block(blocker.id, target.id)
                    memberBlockService.getBlockedMemberIds(blocker.id) shouldContainExactly listOf(target.id)
                }
            }
            `when`("자기 자신을 차단하면") {
                then("SELF_BLOCK_FORBIDDEN 예외를 던진다") {
                    val blocker = newMember()
                    shouldThrow<BusinessException> {
                        memberBlockService.block(blocker.id, blocker.id)
                    }.errorCode shouldBe ErrorCode.SELF_BLOCK_FORBIDDEN
                }
            }
            `when`("존재하지 않는 회원을 차단하면") {
                then("BLOCK_TARGET_NOT_FOUND 예외를 던진다") {
                    val blocker = newMember()
                    shouldThrow<BusinessException> {
                        memberBlockService.block(blocker.id, 999_999_999L)
                    }.errorCode shouldBe ErrorCode.BLOCK_TARGET_NOT_FOUND
                }
            }
            `when`("탈퇴한 회원을 차단하면") {
                then("BLOCK_TARGET_NOT_FOUND 예외를 던진다") {
                    val blocker = newMember()
                    val withdrawn = newMember()
                    memberService.withdraw(withdrawn.id)
                    shouldThrow<BusinessException> {
                        memberBlockService.block(blocker.id, withdrawn.id)
                    }.errorCode shouldBe ErrorCode.BLOCK_TARGET_NOT_FOUND
                }
            }
            `when`("이미 차단 중인 회원을 다시 차단하면") {
                then("예외 없이 성공하고 행은 1개로 유지된다") {
                    val blocker = newMember()
                    val target = newMember()
                    memberBlockService.block(blocker.id, target.id)
                    val firstRowId = memberBlockRepository.findAnyByPair(blocker.id, target.id).shouldNotBeNull().id

                    memberBlockService.block(blocker.id, target.id)

                    memberBlockService.getBlockedMemberIds(blocker.id) shouldContainExactly listOf(target.id)
                    memberBlockRepository.findAnyByPair(blocker.id, target.id).shouldNotBeNull().id shouldBe firstRowId
                }
            }
        }

        given("차단 해제") {
            `when`("차단 중인 회원을 해제하면") {
                then("차단 목록에서 빠진다") {
                    val blocker = newMember()
                    val target = newMember()
                    memberBlockService.block(blocker.id, target.id)

                    memberBlockService.unblock(blocker.id, target.id)

                    memberBlockService.getBlockedMemberIds(blocker.id) shouldBe emptyList()
                }
            }
            `when`("차단한 적 없는 회원을 해제하면") {
                then("예외 없이 성공한다") {
                    val blocker = newMember()
                    memberBlockService.unblock(blocker.id, 999_999_999L)
                    memberBlockService.getBlockedMemberIds(blocker.id) shouldBe emptyList()
                }
            }
            `when`("이미 해제한 회원을 다시 해제하면") {
                then("예외 없이 성공한다") {
                    val blocker = newMember()
                    val target = newMember()
                    memberBlockService.block(blocker.id, target.id)
                    memberBlockService.unblock(blocker.id, target.id)
                    memberBlockService.unblock(blocker.id, target.id)
                    memberBlockService.getBlockedMemberIds(blocker.id) shouldBe emptyList()
                }
            }
        }

        given("재차단 — 소프트삭제 부활") {
            `when`("차단→해제→재차단을 수행하면") {
                then("UNIQUE 위반 없이 기존 행이 ACTIVE 로 되살아난다") {
                    val blocker = newMember()
                    val target = newMember()
                    memberBlockService.block(blocker.id, target.id)
                    val originalRowId = memberBlockRepository.findAnyByPair(blocker.id, target.id).shouldNotBeNull().id
                    memberBlockService.unblock(blocker.id, target.id)

                    memberBlockService.block(blocker.id, target.id)

                    memberBlockService.getBlockedMemberIds(blocker.id) shouldContainExactly listOf(target.id)
                    val revived = memberBlockRepository.findAnyByPair(blocker.id, target.id).shouldNotBeNull()
                    revived.id shouldBe originalRowId
                    revived.isActive().shouldBeTrue()
                }
            }
        }

        given("차단 목록 조회") {
            `when`("아무도 차단하지 않은 회원이 조회하면") {
                then("빈 목록을 준다") {
                    memberBlockService.getBlockedMemberIds(newMember().id) shouldBe emptyList()
                }
            }
        }
    }
}
