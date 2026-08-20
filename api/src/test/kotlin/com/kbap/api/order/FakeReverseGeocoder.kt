package com.kbap.api.order

import com.kbap.common.port.place.ReverseGeocoder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

class FakeReverseGeocoder : ReverseGeocoder {
    private val addresses = mutableMapOf<Pair<String, String>, String>()
    var failAll: Boolean = false

    fun program(latitude: String, longitude: String, roadAddress: String) {
        addresses[latitude to longitude] = roadAddress
    }

    override fun getRoadAddressOrNull(latitude: BigDecimal, longitude: BigDecimal): String? {
        if (failAll) return null
        return addresses[latitude.toPlainString() to longitude.toPlainString()]
    }
}

@Configuration
class FakeReverseGeocoderConfig {
    @Bean
    fun fakeReverseGeocoder(): FakeReverseGeocoder = FakeReverseGeocoder()
}
