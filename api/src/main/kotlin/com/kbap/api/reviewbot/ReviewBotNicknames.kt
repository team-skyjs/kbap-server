package com.kbap.api.reviewbot

import com.kbap.common.domain.member.model.CountryCode
import kotlin.random.Random

object ReviewBotNicknames {
    fun allNames(): List<String> = emptyList()

    fun poolOf(countryCode: CountryCode): List<String> = emptyList()

    fun isEligible(name: String): Boolean = true

    fun mark(name: String): String = name

    fun pick(countryCode: CountryCode, used: Set<String>, random: Random): String? = "bot"
}
