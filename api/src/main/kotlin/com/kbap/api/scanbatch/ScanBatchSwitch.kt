package com.kbap.api.scanbatch

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class ScanBatchSwitch(
    @Value("\${kbap.scan.batch-enabled:false}") var enabled: Boolean,
)
