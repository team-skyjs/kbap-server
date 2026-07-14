package com.kbap.domain.research.ensemble

data class EnsemblePolicy(
    val scoreWeight: Double = 0.6,
    val floor: Int = 1,
) {
    companion object {
        val DEFAULT = EnsemblePolicy()
    }
}
