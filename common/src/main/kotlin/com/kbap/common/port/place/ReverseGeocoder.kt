package com.kbap.common.port.place

import java.math.BigDecimal

interface ReverseGeocoder {
    fun getRoadAddressOrNull(latitude: BigDecimal, longitude: BigDecimal): String?
}
