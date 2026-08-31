package com.kbap.common.domain.block

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.block.model.MemberBlock
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class MemberBlockJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var memberBlockRepository: MemberBlockJpaRepository

    init {
        given("차단 행 저장과 ACTIVE 조회") {
            val blocker = 10L
            memberBlockRepository.save(MemberBlock(blockerMemberId = blocker, blockedMemberId = 11L))
            memberBlockRepository.save(MemberBlock(blockerMemberId = blocker, blockedMemberId = 12L))
            memberBlockRepository.save(MemberBlock(blockerMemberId = 99L, blockedMemberId = 11L))

            `when`("findBlockedMemberIds 로 조회하면") {
                then("해당 blocker 의 차단 대상 id 만 준다") {
                    memberBlockRepository.findBlockedMemberIds(blocker)
                        .shouldContainExactlyInAnyOrder(11L, 12L)
                }
            }
            `when`("findByBlockerMemberIdAndBlockedMemberId 로 쌍을 조회하면") {
                then("ACTIVE 행을 준다") {
                    memberBlockRepository.findByBlockerMemberIdAndBlockedMemberId(blocker, 11L)
                        .shouldNotBeNull()
                        .blockedMemberId shouldBe 11L
                }
            }
        }

        given("소프트 삭제된 차단 행") {
            val blocker = 20L
            val blocked = 21L
            val row = memberBlockRepository.save(MemberBlock(blockerMemberId = blocker, blockedMemberId = blocked))
            row.delete()
            memberBlockRepository.save(row)

            `when`("일반 조회를 하면") {
                then("DELETED 행은 잡히지 않는다") {
                    memberBlockRepository.findBlockedMemberIds(blocker) shouldBe emptyList()
                    memberBlockRepository.findByBlockerMemberIdAndBlockedMemberId(blocker, blocked).shouldBeNull()
                }
            }
            `when`("findAnyByPair 로 상태 무시 조회를 하면") {
                then("DELETED 행도 준다 — 재차단 부활 대상 탐지") {
                    val found = memberBlockRepository.findAnyByPair(blocker, blocked)
                    found.shouldNotBeNull()
                    found.id shouldBe row.id
                    found.isDeleted().shouldBeTrue()
                }
            }
            `when`("존재하지 않는 쌍을 findAnyByPair 로 조회하면") {
                then("null 을 준다") {
                    memberBlockRepository.findAnyByPair(blocker, 999L).shouldBeNull()
                }
            }
        }
    }
}
