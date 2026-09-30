package com.kbap.api.reviewbot

import com.kbap.common.domain.member.model.CountryCode
import kotlin.random.Random

object ReviewBotCountries {
    private val WEIGHTS: List<Pair<CountryCode, Int>> = listOf(
        CountryCode.JP to 22,
        CountryCode.TW to 15,
        CountryCode.US to 15,
        CountryCode.CN to 14,
        CountryCode.TH to 9,
        CountryCode.VN to 7,
        CountryCode.HK to 6,
        CountryCode.SG to 4,
        CountryCode.PH to 4,
        CountryCode.MY to 4,
    )

    private val LANGUAGES: Map<CountryCode, String> = mapOf(
        CountryCode.JP to "Japanese",
        CountryCode.TW to "Traditional Chinese",
        CountryCode.HK to "Traditional Chinese",
        CountryCode.CN to "Simplified Chinese",
        CountryCode.TH to "Thai",
        CountryCode.VN to "Vietnamese",
    )

    const val ENGLISH = "English"
    const val ENGLISH_SHARE = 0.7

    fun pick(random: Random): CountryCode {
        var roll = random.nextInt(WEIGHTS.sumOf { it.second })
        for ((country, weight) in WEIGHTS) {
            if (roll < weight) return country
            roll -= weight
        }
        return WEIGHTS.first().first
    }

    fun languageFor(countryCode: String?, random: Random): String {
        if (random.nextDouble() < ENGLISH_SHARE) return ENGLISH
        return countryCode?.let { code -> CountryCode.entries.firstOrNull { it.name == code } }?.let(LANGUAGES::get) ?: ENGLISH
    }

    fun rating(random: Random): Int = when (random.nextInt(10)) {
        in 0..1 -> 3
        in 2..6 -> 4
        else -> 5
    }
}
