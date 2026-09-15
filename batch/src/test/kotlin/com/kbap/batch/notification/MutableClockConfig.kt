package com.kbap.batch.notification

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class MutableClock(
    private var instant: Instant = Instant.now(),
    private val zone: ZoneId = SEOUL,
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)

    override fun instant(): Instant = instant

    fun set(instant: Instant) {
        this.instant = instant
    }

    fun setSeoul(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0) {
        set(ZonedDateTime.of(year, month, day, hour, minute, second, 0, SEOUL).toInstant())
    }

    companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

@TestConfiguration
class MutableClockConfig {
    @Bean
    @Primary
    fun mutableClock(): MutableClock = MutableClock()
}
