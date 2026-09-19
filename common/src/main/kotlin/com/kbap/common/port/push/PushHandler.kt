package com.kbap.common.port.push

import com.kbap.common.domain.notification.PushDispatchResult
import com.kbap.common.domain.notification.PushRequest

fun interface PushHandler {
    fun send(request: PushRequest): PushDispatchResult
}
