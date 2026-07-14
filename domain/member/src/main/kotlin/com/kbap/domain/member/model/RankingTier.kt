package com.kbap.domain.member.model

enum class RankingTier(val level: Int, val minScore: Int) {
    NEWCOMER(1, 0),
    TASTER(2, 30),
    EXPLORER(3, 80),
    REGULAR(4, 180),
    GOURMET(5, 350),
    KFOOD_MASTER(6, 600),
    KOREAN_AT_HEART(7, 1000),
    ;

    val key: String = name.lowercase()

    val next: RankingTier?
        get() = entries.getOrNull(ordinal + 1)

    companion object {
        fun of(score: Int): RankingTier = entries.last { score >= it.minScore }
    }
}
