package com.kbap.common.domain.member

import com.kbap.common.domain.member.model.MemberSurvey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MemberSurveyJpaRepository : JpaRepository<MemberSurvey, Long> {
    fun findByMemberId(memberId: Long): MemberSurvey?

    fun existsByMemberId(memberId: Long): Boolean

    @Modifying
    @Query(nativeQuery = true, value = "DELETE FROM member_survey WHERE member_id = :memberId")
    fun purgeByMemberId(@Param("memberId") memberId: Long): Int
}
