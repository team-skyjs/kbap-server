package com.kbap.domain.scan.dto

internal data class MenuNameLookup(
    val koreanName: String,
    val matchKey: String,
    val confirmedByInterpreter: Boolean,
)
