package com.kbap.common.port.exchange

import com.kbap.common.domain.CurrencyCode
import java.math.BigDecimal

interface ExchangeRateClient {
    fun getKrwPerUnitOrNull(currency: CurrencyCode): BigDecimal?
}
