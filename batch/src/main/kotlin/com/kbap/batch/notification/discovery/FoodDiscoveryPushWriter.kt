package com.kbap.batch.notification.discovery

import com.kbap.batch.notification.PushDispatchMetric
import com.kbap.batch.notification.nowInJvmZone
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.bookmark.BookmarkJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodMarketingPopularJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.notification.NotificationJpaRepository
import com.kbap.common.domain.notification.PushRequest
import com.kbap.common.domain.notification.PushTargetResolver
import com.kbap.common.domain.notification.model.Notification
import com.kbap.common.domain.notification.model.NotificationType
import com.kbap.common.port.push.PushHandler
import com.kbap.common.util.ImageUrls
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamWriter
import java.time.Clock
import java.time.Duration
import kotlin.random.Random

/**
 * 회원 id 청크를 받아 회원마다 안전한 인기 음식 하나를 골라 FOOD_DISCOVERY 광고 푸시를 보낸다.
 * "이번 슬롯에 이미 보냈는가" 는 Reader 의 쿼리가, "받을 수 있는 기기인가" 는 PushTargetResolver 가 거른다 — 여기서는 음식 선택만 한다.
 */
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
    private val imagePublicBaseUrl: String,
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
        logger.info("음식 발견 후보 적재 slot={} open={} pool={}", slot, open, candidates.foods.size)
    }

    override fun write(chunk: Chunk<out Long>) {
        if (!open) {
            logger.info("음식 발견 발송 금지 시간대라 건너뜁니다 slot={} members={}", slot, chunk.size())
            return
        }
        // 1. 실제로 받을 수 있는 회원(동의·소식 설정·유효 토큰)과 그 회원의 기기 언어들
        val langsByMember = targetResolver.resolve(chunk.items, TYPE)
            .groupBy({ it.memberId!! }, { LanguageCode.from(it.lang) })
            .mapValues { it.value.toSet() }
        if (langsByMember.isEmpty()) return
        val memberIds = langsByMember.keys

        // 2. 회원별 회피 재료, 그리고 다시 고르면 안 되는 음식(90일 내 발송 + 북마크)
        val avoidedByMember = memberRepository.findAllById(memberIds).associate { it.id to it.profile.avoidance().codeNames }
        val excludedByMember = (
            notificationRepository.findFoodIdsSince(memberIds, TYPE, clock.nowInJvmZone() - HISTORY).map { it.memberId to it.foodId } +
                bookmarkRepository.findByMemberIdIn(memberIds).map { it.memberId to it.foodId }
            ).groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

        // 3. 회원마다 음식 하나. 못 고른 회원은 건너뛰고 지표만 남긴다
        val foodByMember = avoidedByMember.mapNotNull { (memberId, avoided) ->
            candidates.pick(avoided, langsByMember.getValue(memberId), excludedByMember[memberId].orEmpty(), random)?.let { memberId to it }
        }.toMap()
        val skipped = avoidedByMember.size - foodByMember.size
        meterRegistry.counter(PushDispatchMetric.FOOD_DISCOVERY_NO_CANDIDATE, "slot", slot).increment(skipped.toDouble())

        // 4. 같은 음식이 뽑힌 회원끼리 묶어 요청 1회 (문구·썸네일이 음식에 종속이라서)
        val results = foodByMember.entries.groupBy({ it.value }, { it.key }).map { (food, members) -> handler.send(request(food, members)) }
        val sent = results.sumOf { it.sent }
        val failed = results.sumOf { it.failed }
        meterRegistry.counter(PushDispatchMetric.NAME, "type", TYPE.name, "result", "sent").increment(sent.toDouble())
        meterRegistry.counter(PushDispatchMetric.NAME, "type", TYPE.name, "result", "failed").increment(failed.toDouble())
        logger.info("음식 발견 발송 slot={} members={} skipped={} sent={} failed={}", slot, memberIds.size, skipped, sent, failed)
    }

    private fun request(food: Food, members: List<Long>) = PushRequest(
        TYPE,
        members,
        argsByLang = LanguageCode.entries.mapNotNull { lang ->
            FoodDiscoveryCandidates.description(food, lang)?.let { lang to mapOf("food" to food.displayName(lang), "description" to it) }
        }.toMap(),
        data = mapOf(Notification.DATA_FOOD_ID to food.id),
        ttlSeconds = ttlSeconds,
        // 상대 키인데 CDN 베이스가 비어 있으면 잘못된 URL 을 Expo 에 보내지 않도록 썸네일을 뺀다
        imageUrl = ImageUrls.resolve(imagePublicBaseUrl, food.imageRef)?.takeIf(ImageUrls::isAbsoluteUrl),
    )

    companion object {
        private val TYPE = NotificationType.FOOD_DISCOVERY
        private val HISTORY: Duration = Duration.ofDays(90)
        private val logger = LoggerFactory.getLogger(FoodDiscoveryPushWriter::class.java)
    }
}
