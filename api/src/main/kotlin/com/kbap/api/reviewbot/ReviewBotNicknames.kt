package com.kbap.api.reviewbot

import com.kbap.common.domain.member.model.CountryCode
import kotlin.random.Random

object ReviewBotNicknames {
    private val JAPANESE = listOf(
        "kenta", "yuki", "mika", "rina", "sota", "haruka", "daiki", "yuto", "akira", "naoki",
        "riku", "saki", "hana", "kaito", "shota", "ayaka", "misaki", "takumi", "nanami", "hiroki",
    )
    private val CHINESE = listOf(
        "meiling", "jiahao", "lina", "bowen", "yating", "weilin", "zihan", "yuxin", "haoran", "xinyi",
        "chenyu", "jiayi", "minghao", "yichen", "peishan", "kaiwen", "zixuan", "ruoxi",
    )
    private val ENGLISH = listOf(
        "kevin", "jason", "megan", "sarah", "tyler", "oliver", "sophia", "lucas", "mason", "julia",
        "david", "simon", "peter", "helen", "rachel", "daniel", "nolan", "ethan", "chloe", "grace",
        "henry", "emily", "jacob", "logan", "nathan", "amber", "carlos", "marco", "angela", "bianca",
    )
    private val THAI = listOf(
        "niran", "somchai", "anong", "kamon", "ploy", "mali", "arthit", "siri", "nicha", "tanawat",
    )
    private val VIETNAMESE = listOf(
        "minh", "linh", "thanh", "quang", "hieu", "trang", "huong", "khanh", "tuan", "ngoc", "phuong",
    )
    private val POOLS = listOf(JAPANESE, CHINESE, ENGLISH, THAI, VIETNAMESE)
    private val NAME = Regex("^[a-z]{4,13}$")

    init {
        val ineligible = allNames().filterNot(::isEligible)
        require(ineligible.isEmpty()) { "봇 닉네임 풀에 조건에 안 맞는 이름이 있습니다: $ineligible" }
    }

    fun allNames(): List<String> = POOLS.flatten()

    fun poolOf(countryCode: CountryCode): List<String> = when (countryCode) {
        CountryCode.JP -> JAPANESE
        CountryCode.TW, CountryCode.HK, CountryCode.CN -> CHINESE
        CountryCode.TH -> THAI
        CountryCode.VN -> VIETNAMESE
        else -> ENGLISH
    }

    fun isEligible(name: String): Boolean =
        NAME.matches(name) && name[2] != name[3] && (name.length < 5 || name[3] != name[4])

    fun mark(name: String): String = name.substring(0, 4) + name[3] + name.substring(4)

    fun pick(countryCode: CountryCode, used: Set<String>, random: Random): String? =
        (poolOf(countryCode).shuffled(random) + allNames().shuffled(random))
            .asSequence()
            .map(::mark)
            .firstOrNull { it !in used }
}
