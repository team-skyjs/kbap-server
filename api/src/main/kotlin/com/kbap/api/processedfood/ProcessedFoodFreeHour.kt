package com.kbap.api.processedfood

import org.springframework.stereotype.Component
import java.time.Clock

@Component
class ProcessedFoodFreeHour {
    var clock: Clock = Clock.systemDefaultZone()
}
