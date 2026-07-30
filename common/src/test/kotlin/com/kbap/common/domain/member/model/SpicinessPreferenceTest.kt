package com.kbap.common.domain.member.model

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class SpicinessPreferenceTest : BehaviorSpec({

    given("SpicinessPreference 단계 집합") {
        `when`("전체 값을 나열하면") {
            then("SKIP·NONE·MILD·MEDIUM·HOT·EXTREME 6단계다") {
                SpicinessPreference.entries.map { it.name } shouldBe
                    listOf("SKIP", "NONE", "MILD", "MEDIUM", "HOT", "EXTREME")
            }
        }
    }

    given("SpicinessPreference.from — 문자열 변환") {
        `when`("6단계에 있는 문자열이면") {
            then("해당 단계로 변환한다") {
                SpicinessPreference.from("HOT") shouldBe SpicinessPreference.HOT
                SpicinessPreference.from("SKIP") shouldBe SpicinessPreference.SKIP
            }
        }

        `when`("6단계에 없는 문자열이면") {
            then("MEMBER-009 로 거절한다") {
                listOf("SUPER_HOT", "5", "-1", "hot", "").forEach { invalid ->
                    val e = shouldThrow<BusinessException> { SpicinessPreference.from(invalid) }
                    e.errorCode shouldBe ErrorCode.INVALID_SPICINESS_PREFERENCE
                }
            }
        }
    }
})
