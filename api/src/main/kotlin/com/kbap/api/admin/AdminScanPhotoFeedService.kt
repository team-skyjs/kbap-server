package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.order.OrderJpaRepository
import com.kbap.common.domain.order.ScanPhotoFeedRow
import com.kbap.common.util.ImageUrls
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.Base64

@Service
class AdminScanPhotoFeedService(
    private val orders: OrderJpaRepository,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    @Transactional(readOnly = true)
    fun getScanPhotoFeed(isBot: Boolean, memberId: Long?, cursor: String?): AdminScanPhotoFeed {
        val rows = cursor?.let { raw ->
            val (at, kind, id) = decode(raw)
            orders.findScanPhotoFeedAfter(isBot, memberId, at, kind.name, id, PAGE_SIZE + 1)
        } ?: orders.findScanPhotoFeed(isBot, memberId, PAGE_SIZE + 1)
        val page = rows.take(PAGE_SIZE)
        val hasNext = rows.size > PAGE_SIZE
        return AdminScanPhotoFeed(
            items = page.map { it.toItem() },
            nextCursor = if (hasNext) page.last().let { encode(it.createdAt, ScanPhotoKind.valueOf(it.kind), it.rowId) } else null,
            hasNext = hasNext,
        )
    }

    private fun ScanPhotoFeedRow.toItem() = AdminScanPhotoFeedItem(
        imageUrl = ImageUrls.resolve(imagePublicBaseUrl, path),
        createdAt = createdAt,
        memberId = memberId,
        nickname = nickname,
        countryCode = countryCode,
        isBot = bot.toInt() != 0,
        memberStatus = memberStatus,
        kind = ScanPhotoKind.valueOf(kind),
        sourceId = sourceId,
    )

    private fun encode(at: LocalDateTime, kind: ScanPhotoKind, rowId: Long): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$at|${kind.name}|$rowId".toByteArray(Charsets.UTF_8))

    private fun decode(cursor: String): Triple<LocalDateTime, ScanPhotoKind, Long> = runCatching {
        val (at, kind, id) = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8).split('|').also { require(it.size == 3) }
        Triple(LocalDateTime.parse(at), ScanPhotoKind.valueOf(kind), id.toLong())
    }.getOrElse { throw BusinessException(ErrorCode.INVALID_CURSOR) }

    companion object {
        const val PAGE_SIZE = 24
    }
}
