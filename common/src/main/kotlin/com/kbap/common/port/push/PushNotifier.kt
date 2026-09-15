package com.kbap.common.port.push

import com.kbap.common.domain.notification.PushDispatchResult
import com.kbap.common.domain.notification.PushRequest

fun interface PushNotifier {
    fun send(request: PushRequest): PushDispatchResult
}
