package com.kbap.common.infra.push

import com.kbap.common.domain.notification.PushDispatchResult
import com.kbap.common.domain.notification.PushDispatchService
import com.kbap.common.domain.notification.PushOutcome
import com.kbap.common.domain.notification.PushRequest
import com.kbap.common.port.push.PushMessage
import com.kbap.common.port.push.PushHandler
import com.kbap.common.port.push.PushClient

class ExpoPushHandler(
    private val dispatchService: PushDispatchService,
    private val pushClient: PushClient,
) : PushHandler {
    override fun send(request: PushRequest): PushDispatchResult {
        val prepared = dispatchService.prepare(request)
        if (prepared.isEmpty()) return PushDispatchResult(sent = 0, failed = 0)
        val tickets = pushClient.send(prepared.messages.map { PushMessage(it.to, it.title, it.body, it.data, channelId = it.channelId, ttlSeconds = it.ttlSeconds) })
        return dispatchService.record(prepared, tickets.map { PushOutcome(it.ok, it.id, it.error) })
    }
}
