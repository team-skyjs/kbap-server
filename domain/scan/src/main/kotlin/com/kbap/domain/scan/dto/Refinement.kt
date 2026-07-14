package com.kbap.domain.scan.dto

import com.kbap.core.scan.InterpretedName

internal data class Refinement(val byItemIndex: Map<Int, InterpretedName>?, val degraded: Boolean)
