package com.kbap.api.review

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.review.ReviewSort
import com.kbap.common.util.CursorParser
import java.util.Base64

object ReviewListCursor {
    data class Position(val metric: Long?, val id: Long)

    fun parse(raw: String?, sort: ReviewSort): Position? {
        if (raw.isNullOrBlank()) return null
        if (sort == ReviewSort.LATEST) {
            return Position(metric = null, id = CursorParser.parse(raw)!!)
        }
        val decoded = runCatching { String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8) }
            .getOrElse { throw BusinessException(ErrorCode.INVALID_CURSOR) }
        val parts = decoded.split("_")
        if (parts.size != 2) throw BusinessException(ErrorCode.INVALID_CURSOR)
        val metric = parts[0].toLongOrNull()?.takeIf { it >= 0 }
        val id = parts[1].toLongOrNull()?.takeIf { it >= 0 }
        if (metric == null || id == null) throw BusinessException(ErrorCode.INVALID_CURSOR)
        return Position(metric = metric, id = id)
    }

    fun encode(sort: ReviewSort, metric: Long, id: Long): String =
        if (sort == ReviewSort.LATEST) {
            id.toString()
        } else {
            Base64.getUrlEncoder().withoutPadding().encodeToString("${metric}_$id".toByteArray(Charsets.UTF_8))
        }
}
