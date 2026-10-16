package com.kbap.api.reviewbot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.domain.image.BotPhotoJpaRepository
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadedImage
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.port.storage.StorageObjectMetadata
import com.kbap.common.util.LikeWildcards
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReviewBotPhotoCatalog(
    private val photos: BotPhotoJpaRepository,
    private val images: UploadedImageJpaRepository,
    private val members: MemberJpaRepository,
) {
    @Transactional(readOnly = true)
    fun getRecords(prefix: String): List<ReviewBotPhotoRecord> =
        photos.getRecords(LikeWildcards.escape(prefix) + "%").map {
            ReviewBotPhotoRecord(it.path, it.memberId, it.status == "ACTIVE" && it.ownerKind == "ACTIVE_BOT")
        }

    @Transactional(readOnly = true)
    fun getOrdinaryReferencedPaths(paths: Collection<String>): Set<String> =
        if (paths.isEmpty()) emptySet() else photos.getOrdinaryReferencedPaths(jacksonObjectMapper().writeValueAsString(paths)).toSet()

    @Transactional
    fun register(ownerId: Long, path: String, metadata: StorageObjectMetadata): Boolean {
        val owner = members.findById(ownerId).orElse(null) ?: return false
        if (!owner.isBot || owner.memberStatus != MemberStatus.ACTIVE) return false
        if (photos.getRecords(LikeWildcards.escape(path)).isNotEmpty() || photos.hasOrdinaryReference(path) > 0) return false
        images.save(UploadedImage(memberId = ownerId, path = path, contentType = metadata.contentType, sizeBytes = metadata.sizeBytes))
        return true
    }
}

data class ReviewBotPhotoRecord(val path: String, val ownerId: Long?, val usableOwner: Boolean)
