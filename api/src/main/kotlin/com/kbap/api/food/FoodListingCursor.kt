package com.kbap.api.food

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import java.nio.ByteBuffer
import java.security.MessageDigest

data class FoodListingCursor(val context: String, val revision: String, val lastId: Long) {
    fun encode(): String = "fl1.$context.$revision.$lastId"

    fun verify(expectedContext: String, expectedRevision: String) {
        if (context != expectedContext) throw BusinessException(ErrorCode.INVALID_CURSOR)
        if (revision != expectedRevision) throw BusinessException(ErrorCode.FOOD_LISTING_STALE)
    }

    companion object {
        private val FORMAT = Regex("fl1\\.([a-f0-9]{64})\\.([a-f0-9]{64})\\.([1-9][0-9]{0,18})")

        fun parse(raw: String?): FoodListingCursor? {
            if (raw == null) return null
            val parts = FORMAT.matchEntire(raw)?.groupValues ?: throw BusinessException(ErrorCode.INVALID_CURSOR)
            val id = parts[3].toLongOrNull() ?: throw BusinessException(ErrorCode.INVALID_CURSOR)
            return FoodListingCursor(parts[1], parts[2], id)
        }

        fun fingerprint(values: Iterable<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            values.forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
