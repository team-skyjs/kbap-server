package com.kbap.batch.notification

import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

fun Clock.nowInJvmZone(): LocalDateTime = LocalDateTime.ofInstant(instant(), ZoneId.systemDefault())
