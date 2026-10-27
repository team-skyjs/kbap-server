package com.kbap.api.reviewbot

import com.kbap.api.image.ImageUploadProperties
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadedImage
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.review.model.Review
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import kotlin.random.Random

@Component
class ReviewBotPhotoPool(
    private val uploadedImageRepository: UploadedImageJpaRepository,
    private val memberRepository: MemberJpaRepository,
    uploadProperties: ImageUploadProperties,
) {
    val prefix: String = poolPrefix(uploadProperties.keyPrefix)

    @Transactional(readOnly = true)
    fun ownerIds(): Set<Long> = memberRepository.findBotMemberIdsAnyStatus().toSet()

    @Transactional(readOnly = true)
    fun snapshot(ownerIds: Set<Long>): PoolSnapshot =
        PoolSnapshot(
            uploadedImageRepository.findByPathStartingWithOrderByPath(prefix)
                .filter { it.memberId in ownerIds }
                .mapNotNull { row -> keyOfPath(row.path)?.let { key -> key to row.path } }
                .groupBy({ it.first }, { it.second }),
        )

    class PoolSnapshot(private val pathsByKey: Map<String, List<String>>) {
        val keyCount: Int get() = pathsByKey.size

        fun pathsFor(koreanName: String): List<String> = pathsByKey[keyOf(koreanName)].orEmpty()

        fun hasPool(koreanName: String): Boolean = pathsFor(koreanName).isNotEmpty()
    }

    companion object {
        private val OBJECT_PATTERN = Regex("""^(?:.*/)?images/review-bot/([0-9a-f]{12})/\d+\.webp$""")

        fun keyOf(koreanName: String): String =
            MessageDigest.getInstance("SHA-256").digest(koreanName.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .substring(0, 12)

        fun keyOfPath(path: String): String? = OBJECT_PATTERN.matchEntire(path)?.groupValues?.get(1)

        fun poolPrefix(keyPrefix: String): String {
            val trimmed = keyPrefix.trim('/')
            return (if (trimmed.isEmpty()) "" else "$trimmed/") + UploadedImage.REVIEW_BOT_SEGMENT
        }

        fun pickCombination(pool: List<String>, previous: Set<String>, random: Random): List<String> {
            val largest = minOf(Review.MAX_IMAGE_COUNT, pool.size)
            val candidates = (1..largest).flatMap { size -> combinations(pool, size) }.filter { it.toSet() != previous }
            return candidates.ifEmpty { listOf(pool.take(largest)) }.random(random)
        }

        private fun combinations(items: List<String>, size: Int): List<List<String>> =
            when {
                size == 0 -> listOf(emptyList())
                items.size < size -> emptyList()
                else -> combinations(items.drop(1), size - 1).map { listOf(items.first()) + it } + combinations(items.drop(1), size)
            }
    }
}
