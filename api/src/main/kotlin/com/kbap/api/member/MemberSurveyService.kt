package com.kbap.api.member

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.MemberSurveyJpaRepository
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.MemberSurvey
import com.kbap.common.domain.member.model.SurveyAnswers
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

@Service
class MemberSurveyService(
    private val memberRepository: MemberJpaRepository,
    private val memberSurveyRepository: MemberSurveyJpaRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    fun answerSurvey(memberId: Long, answers: SurveyAnswers): MemberSurveyResult =
        try {
            upsertUnderMemberLock(memberId, answers)
        } catch (e: DataIntegrityViolationException) {
            upsertUnderMemberLock(memberId, answers)
        }

    private fun upsertUnderMemberLock(memberId: Long, answers: SurveyAnswers): MemberSurveyResult =
        transaction.execute {
            val member = memberRepository.findByIdForUpdate(memberId)?.takeIf { it.memberStatus == MemberStatus.ACTIVE }
                ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
            val now = LocalDateTime.now()
            val survey = memberSurveyRepository.findByMemberId(member.id)
                ?.apply { answer(answers, now) }
                ?: memberSurveyRepository.save(MemberSurvey.answeredBy(member.id, answers, now))
            MemberSurveyResult.of(survey)
        }!!
}
