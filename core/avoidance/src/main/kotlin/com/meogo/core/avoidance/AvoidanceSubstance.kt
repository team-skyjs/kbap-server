package com.meogo.core.avoidance

import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.lang.LocalizedText
import com.meogo.core.kernel.stereotype.AggregateRoot

@AggregateRoot
class AvoidanceSubstance private constructor(
    val id: Long,
    val code: AvoidanceSubstanceCode,
    val name: LocalizedText,
) {
    fun displayName(lang: LanguageCode): String = name.resolve(lang)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AvoidanceSubstance) return false
        return code == other.code
    }

    override fun hashCode(): Int = code.hashCode()

    companion object {
        fun reconstitute(
            id: Long,
            code: AvoidanceSubstanceCode,
            name: LocalizedText,
        ): AvoidanceSubstance =
            AvoidanceSubstance(
                id = id,
                code = code,
                name = name,
            )
    }
}
