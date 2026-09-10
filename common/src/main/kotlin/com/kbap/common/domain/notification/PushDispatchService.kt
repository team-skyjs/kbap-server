package com.kbap.common.domain.notification

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.notification.model.Notification
import com.kbap.common.domain.notification.model.NotificationDevice
import com.kbap.common.domain.notification.model.NotificationDispatch
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PushDispatchService(
    private val targetResolver: PushTargetResolver,
    private val renderer: PushMessageRenderer,
    private val notificationRepository: NotificationJpaRepository,
    private val dispatchRepository: NotificationDispatchJpaRepository,
) {
    @Transactional
    fun prepare(request: PushRequest): PreparedPush {
        val devices = targetResolver.resolve(request.memberIds, request.type)
        val messages = mutableListOf<PushEnvelope>()
        val dispatchIds = mutableListOf<Long>()

        devices.groupBy { it.memberId!! }.forEach { (memberId, memberDevices) ->
            val notification = saveInboxNotification(memberId, memberDevices, request)
            val data = request.data + mapOf(DATA_TYPE to request.type.name, DATA_NOTIFICATION_ID to notification.id)
            notification.data = data

            memberDevices.forEach { device ->
                val content = render(request, device.lang)
                val dispatch = dispatchRepository.save(NotificationDispatch.pending(notification.id, device.id, device.expoToken))
                messages += PushEnvelope(device.expoToken, content.title, content.body, data)
                dispatchIds += dispatch.id
            }
        }
        return PreparedPush(messages, dispatchIds)
    }

    @Transactional
    fun record(prepared: PreparedPush, results: List<PushOutcome>): PushDispatchResult {
        require(results.size == prepared.dispatchIds.size) {
            "발송 결과 수가 dispatch 수와 다릅니다: results=${results.size} dispatches=${prepared.dispatchIds.size}"
        }
        val dispatches = dispatchRepository.findAllById(prepared.dispatchIds).associateBy { it.id }
        var sent = 0
        var failed = 0

        prepared.dispatchIds.zip(results).forEach { (dispatchId, outcome) ->
            val dispatch = dispatches.getValue(dispatchId)
            if (outcome.ok) {
                dispatch.markSent(outcome.ticketId ?: "")
                sent++
            } else {
                dispatch.markFailed(outcome.error ?: UNKNOWN_ERROR)
                failed++
            }
        }
        return PushDispatchResult(sent, failed)
    }

    private fun saveInboxNotification(memberId: Long, devices: List<NotificationDevice>, request: PushRequest): Notification {
        val inboxLang = devices.maxBy { it.updatedAt }.lang
        val content = render(request, inboxLang)
        return notificationRepository.save(Notification.forMember(memberId, request.type, content.title, content.body, null))
    }

    private fun render(request: PushRequest, lang: String): PushContent =
        renderer.render(request.type, LanguageCode.from(lang), request.args, request.marketing)

    companion object {
        private const val DATA_TYPE = "type"
        private const val DATA_NOTIFICATION_ID = "notificationId"
        private const val UNKNOWN_ERROR = "unknown"
    }
}
