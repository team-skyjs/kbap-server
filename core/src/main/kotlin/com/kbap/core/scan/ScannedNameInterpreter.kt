package com.kbap.domain.scan

sealed interface InterpretedName {
    data class StandardName(val korean: String) : InterpretedName {
        init {
            require(korean.isNotBlank()) { "StandardName.korean 은 blank 일 수 없습니다" }
        }
    }

    data object NotFood : InterpretedName
}

interface ScannedNameInterpreter {
    fun interpret(texts: List<String>): List<InterpretedName>
}
