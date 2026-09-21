package com.kbap.common.domain.notification

import com.kbap.common.domain.notification.model.Notification
import com.kbap.common.domain.notification.model.NotificationDevice
import com.kbap.common.domain.notification.model.NotificationDispatch
import com.kbap.common.domain.notification.model.NotificationType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime

data class ReceiptOutcome(
    val ok: Boolean,
    val errorCode: String? = null,
    val message: String? = null,
)

data class ResendPolicy(
    val maxResends: Int,
    val window: Duration,
)

enum class ReceiptResult { DELIVERED, FAILED, RESENT }

data class ReceiptApplyResult(
    val resend: PreparedPush,
    val results: List<Pair<NotificationType, ReceiptResult>>,
)

@Service
class PushReceiptService(
    private val dispatchRepository: NotificationDispatchJpaRepository,
    private val notificationRepository: NotificationJpaRepository,
    private val deviceRepository: NotificationDeviceJpaRepository,
    private val targetResolver: PushTargetResolver,
) {
    @Transactional
    fun apply(outcomes: Map<Long, ReceiptOutcome>, policy: ResendPolicy, now: LocalDateTime): ReceiptApplyResult {
        val dispatches = dispatchRepository.findAllById(outcomes.keys)
        val notifications = notificationRepository.findAllById(dispatches.map { it.notificationId }).associateBy { it.id }
        val attempts = dispatchRepository.findByNotificationIdIn(notifications.keys).groupingBy { it.notificationId }.eachCount()
        val messages = mutableListOf<PushEnvelope>()
        val resendIds = mutableListOf<Long>()

        val results = dispatches.map { dispatch ->
            val outcome = outcomes.getValue(dispatch.id)
            val type = checkNotNull(dispatch.notificationType)
            if (outcome.ok) {
                dispatch.markDelivered()
                return@map type to ReceiptResult.DELIVERED
            }
            closeAsFailed(dispatch, outcome, now)

            val notification = notifications[dispatch.notificationId]
            val target = notification
                ?.takeIf { canResend(outcome, attempts.getValue(it.id), it, policy, now) }
                ?.let { currentTarget(it, dispatch) }
            if (notification == null || target == null) {
                notification?.delete()
                return@map type to ReceiptResult.FAILED
            }
            resendIds += dispatchRepository.save(NotificationDispatch.pending(notification.id, target.id, target.expoToken, type)).id
            messages += envelope(notification, target, policy, now)
            type to ReceiptResult.RESENT
        }
        return ReceiptApplyResult(PreparedPush(messages, resendIds), results)
    }

    private fun closeAsFailed(dispatch: NotificationDispatch, outcome: ReceiptOutcome, now: LocalDateTime) {
        dispatch.markFailed(outcome.errorCode ?: outcome.message ?: UNKNOWN_ERROR)
        when {
            outcome.errorCode == DEVICE_NOT_REGISTERED ->
                dispatch.notificationDeviceId?.let { deviceId ->
                    deviceRepository.findById(deviceId)
                        .filter { it.expoToken == dispatch.expoToken }
                        .ifPresent { it.markTokenInvalid(now) }
                }

            !outcome.isRetryable() ->
                logger.warn("재전송해도 해결되지 않는 푸시 실패 dispatchId={} error={}", dispatch.id, dispatch.error)
        }
    }

    private fun canResend(outcome: ReceiptOutcome, attempts: Int, notification: Notification, policy: ResendPolicy, now: LocalDateTime): Boolean =
        outcome.isRetryable() && attempts <= policy.maxResends && !notification.createdAt.isBefore(now - policy.window)

    private fun ReceiptOutcome.isRetryable(): Boolean = errorCode == null || errorCode == MESSAGE_RATE_EXCEEDED

    private fun currentTarget(notification: Notification, dispatch: NotificationDispatch): NotificationDevice? =
        notification.memberId?.let { memberId ->
            targetResolver.resolve(listOf(memberId), notification.type).firstOrNull { it.id == dispatch.notificationDeviceId }
        }

    private fun envelope(notification: Notification, target: NotificationDevice, policy: ResendPolicy, now: LocalDateTime): PushEnvelope {
        val ttlSeconds = if (notification.type.marketing) Duration.between(now, notification.createdAt + policy.window).seconds.toInt() else null
        return PushEnvelope(target.expoToken, notification.title, notification.body, notification.data.orEmpty(), notification.type.channelId, ttlSeconds)
    }

    private companion object {
        const val DEVICE_NOT_REGISTERED = "DeviceNotRegistered"
        const val MESSAGE_RATE_EXCEEDED = "MessageRateExceeded"
        const val UNKNOWN_ERROR = "unknown"

        val logger = LoggerFactory.getLogger(PushReceiptService::class.java)
    }
}
