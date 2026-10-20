package com.kbap.common.port.publicdata

interface FoodMakerLookup {
    fun getMakerOrNull(reportNo: String): String?
}
