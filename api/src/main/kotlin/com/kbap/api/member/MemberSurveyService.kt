package com.kbap.api.member

import com.kbap.common.domain.member.MemberSurveyJpaRepository
import com.kbap.common.domain.member.model.MemberSurvey
import com.kbap.common.domain.member.model.SurveyAnswers
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class MemberSurveyService(
    private val memberService: MemberService,
    private val memberSurveyRepository: MemberSurveyJpaRepository,
) {
    @Transactional
    fun answerSurvey(memberId: Long, answers: SurveyAnswers): MemberSurveyResult {
        val member = memberService.getMember(memberId)
        val now = LocalDateTime.now()
        val survey = memberSurveyRepository.findByMemberId(member.id)
            ?.apply { answer(answers, now) }
            ?: memberSurveyRepository.save(MemberSurvey.answeredBy(member.id, answers, now))
        return MemberSurveyResult.of(survey)
    }
}
