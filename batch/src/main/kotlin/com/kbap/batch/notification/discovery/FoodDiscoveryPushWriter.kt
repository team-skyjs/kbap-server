package com.kbap.batch.notification.discovery

import com.kbap.batch.notification.PushDispatchMetric
import com.kbap.batch.notification.nowInJvmZone
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.bookmark.BookmarkJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodMarketingPopularJpaRepository
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.notification.NotificationJpaRepository
import com.kbap.common.domain.notification.PushRequest
import com.kbap.common.domain.notification.PushTargetResolver
import com.kbap.common.domain.notification.model.Notification
import com.kbap.common.domain.notification.model.NotificationType
import com.kbap.common.port.push.PushHandler
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamWriter
import java.time.Clock
import java.time.Duration
import kotlin.random.Random

class FoodDiscoveryPushWriter(
    private val handler: PushHandler,
    private val targetResolver: PushTargetResolver,
    private val memberRepository: MemberJpaRepository,
    private val notificationRepository: NotificationJpaRepository,
    private val bookmarkRepository: BookmarkJpaRepository,
    private val popularRepository: FoodMarketingPopularJpaRepository,
    private val foodRepository: FoodJpaRepository,
    private val clock: Clock,
    private val ttlSeconds: Int,
    private val meterRegistry: MeterRegistry,
    private val random: Random = Random.Default,
) : ItemStreamWriter<Long> {
    private var candidates = FoodDiscoveryCandidates(emptyList())
    private var open = false
    private var slot = ""

    override fun open(executionContext: ExecutionContext) {
        open = FoodDiscoverySendWindow.isOpen(clock)
        slot = FoodDiscoverySendWindow.slotTag(clock)
        candidates = FoodDiscoveryCandidates(if (open) foodRepository.findByIdIn(popularRepository.findAllFoodIds()) else emptyList())
        logger.info("음식 발견 후보 적재 slot={} open={} pool={}", slot, open, candidates.ids.size)
    }

    override fun write(chunk: Chunk<out Long>) {
        if (!open) {
            logger.info("음식 발견 발송 금지 시간대라 건너뜁니다 slot={} members={}", slot, chunk.size())
            return
        }
        val langsByMember = targetResolver.resolve(chunk.items, TYPE)
            .groupBy({ it.memberId!! }, { LanguageCode.from(it.lang) })
            .mapValues { it.value.toSet() }
        val memberIds = langsByMember.keys
        if (memberIds.isEmpty()) return

        val avoidedByMember = memberRepository.findAllById(memberIds).associate { it.id to it.profile.avoidance().codeNames }
        val since = clock.nowInJvmZone() - HISTORY
        val excluded = (
            notificationRepository.findFoodIdsSince(memberIds, TYPE, since).map { it.memberId to it.foodId } +
                bookmarkRepository.findByMemberIdIn(memberIds).map { it.memberId to it.foodId }
            ).groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

        val picked = avoidedByMember.mapNotNull { (memberId, avoided) ->
            candidates.pick(avoided, langsByMember.getValue(memberId), excluded[memberId].orEmpty(), random)?.let { memberId to it }
        }
        val skipped = avoidedByMember.size - picked.size
        meterRegistry.counter(PushDispatchMetric.FOOD_DISCOVERY_NO_CANDIDATE, "slot", slot).increment(skipped.toDouble())

        var sent = 0
        var failed = 0
        picked.groupBy({ it.second }, { it.first }).forEach { (candidate, members) ->
            val result = handler.send(
                PushRequest(
                    TYPE,
                    members,
                    argsByLang = candidate.text.mapValues { (_, text) -> mapOf("food" to text.name, "description" to text.description) },
                    data = mapOf(Notification.DATA_FOOD_ID to candidate.id),
                    ttlSeconds = ttlSeconds,
                ),
            )
            sent += result.sent
            failed += result.failed
        }
        meterRegistry.counter(PushDispatchMetric.NAME, "type", TYPE.name, "result", "sent").increment(sent.toDouble())
        meterRegistry.counter(PushDispatchMetric.NAME, "type", TYPE.name, "result", "failed").increment(failed.toDouble())
        logger.info("음식 발견 발송 slot={} members={} skipped={} sent={} failed={}", slot, memberIds.size, skipped, sent, failed)
    }

    companion object {
        private val TYPE = NotificationType.FOOD_DISCOVERY
        private val HISTORY: Duration = Duration.ofDays(90)
        private val logger = LoggerFactory.getLogger(FoodDiscoveryPushWriter::class.java)
    }
}
