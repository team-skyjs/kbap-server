package com.kbap.common.infra.push

import com.kbap.common.domain.notification.PushDispatchResult
import com.kbap.common.domain.notification.PushDispatchService
import com.kbap.common.domain.notification.PushOutcome
import com.kbap.common.domain.notification.PushRequest
import com.kbap.common.port.push.PushMessage
import com.kbap.common.port.push.PushNotifier
import com.kbap.common.port.push.PushSender

class ExpoPushNotifier(
    private val dispatchService: PushDispatchService,
    private val sender: PushSender,
) : PushNotifier {
    override fun send(request: PushRequest): PushDispatchResult {
        val prepared = dispatchService.prepare(request)
        if (prepared.isEmpty()) return PushDispatchResult(sent = 0, failed = 0)
        val tickets = sender.send(prepared.messages.map { PushMessage(it.to, it.title, it.body, it.data, ttlSeconds = it.ttlSeconds) })
        return dispatchService.record(prepared, tickets.map { PushOutcome(it.ok, it.id, it.error) })
    }
}
